/*******************************************************************************
 * Copyright (c) 2017 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Microsoft Corporation - initial API and implementation
*******************************************************************************/

package com.microsoft.java.debug.plugin.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.debug.core.sourcelookup.ISourceContainer;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IModuleDescription;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.sourcelookup.containers.JavaProjectSourceContainer;
import org.eclipse.jdt.launching.sourcelookup.containers.PackageFragmentRootSourceContainer;

import com.microsoft.java.debug.core.DebugException;
import com.microsoft.java.debug.core.StackFrameUtility;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayType;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.Type;

public class JdtUtils {
    private static final String TEST_SCOPE = "test";
    private static final String MAVEN_SCOPE_ATTRIBUTE = "maven.scope";
    private static final String GRADLE_SCOPE_ATTRIBUTE = "gradle_scope";

    /**
     * Returns the module this project represents or null if the Java project doesn't represent any named module.
     */
    public static String getModuleName(IJavaProject project) {
        if (project == null || !JavaRuntime.isModularProject(project)) {
            return null;
        }
        IModuleDescription module;
        try {
            module = project.getModuleDescription();
        } catch (CoreException e) {
            return null;
        }
        return module == null ? null : module.getElementName();
    }

    /**
     * Check if the project is a java project or not.
     */
    public static boolean isJavaProject(IProject project) {
        if (project == null || !project.exists()) {
            return false;
        }
        try {
            if (!project.isNatureEnabled(JavaCore.NATURE_ID)) {
                return false;
            }
        } catch (CoreException e) {
            return false;
        }
        return true;
    }

    /**
     * If the project represents a java project, then convert it to a java project.
     * Otherwise, return null.
     */
    public static IJavaProject getJavaProject(IProject project) {
        if (isJavaProject(project)) {
            return JavaCore.create(project);
        }
        return null;
    }

    /**
     * Given the project name, return the corresponding java project model.
     * If the project doesn't exist or not a java project, return null.
     */
    public static IJavaProject getJavaProject(String projectName) {
        if (StringUtils.isBlank(projectName)) {
            return null;
        }
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root.getProject(projectName);
        return getJavaProject(project);
    }

    /**
     * List all available Java projects of the specified workspace.
     */
    public static List<IJavaProject> listJavaProjects(IWorkspaceRoot workspace) {
        List<IJavaProject> results = new ArrayList<>();
        for (IProject project : workspace.getProjects()) {
            if (isJavaProject(project)) {
                results.add(JavaCore.create(project));
            }
        }
        return results;
    }

    /**
     * Given the project name, return the corresponding project object.
     * If the project doesn't exist, return null.
     */
    public static IProject getProject(String projectName) {
        if (StringUtils.isBlank(projectName)) {
            return null;
        }
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        return root.getProject(projectName);
    }

    /**
     * Compute the fragment roots for each test source folders.
     *
     * @param project the java project.
     * @return the fragment roots for each test source folders.
     */
    public static IPackageFragmentRoot[] getTestPackageFragmentRoots(IJavaProject project) {
        try {
            IPackageFragmentRoot[] packageFragmentRoot = project.getPackageFragmentRoots();
            List<IPackageFragmentRoot> sources = new ArrayList<>();
            for (int i = 0; i < packageFragmentRoot.length; i++) {
                if (packageFragmentRoot[i].getElementType() == IJavaElement.PACKAGE_FRAGMENT_ROOT
                        && packageFragmentRoot[i].getKind() == IPackageFragmentRoot.K_SOURCE) {
                    IClasspathEntry cpe = packageFragmentRoot[i].getResolvedClasspathEntry();

                    if (isTest(cpe)) {
                        sources.add(packageFragmentRoot[i]);
                    }
                }
            }
            return sources.toArray(new IPackageFragmentRoot[0]);
        } catch (JavaModelException e) {
            // ignore
            return new IPackageFragmentRoot[0];
        }
    }

    /**
     * There is an issue on IClasspathEntry#isTest: it will return true if the scope is runtime, so we will this method for testing whether
     * the classpath entry is for test only.
     *
     * @param classpathEntry classpath entry
     * @return whether this classpath entry is only used in test
     */
    public static boolean isTest(final IClasspathEntry classpathEntry) {
        for (IClasspathAttribute attribute : classpathEntry.getExtraAttributes()) {
            if (GRADLE_SCOPE_ATTRIBUTE.equals(attribute.getName()) || MAVEN_SCOPE_ATTRIBUTE.equals(attribute.getName())) {
                return TEST_SCOPE.equals(attribute.getValue());
            }
        }
        return classpathEntry.isTest();
    }

    /**
     * Compute the possible source containers that the specified project could be associated with.
     * <p>
     * If the project name is specified, it will put the source containers parsed from the specified project's
     * classpath entries in the front of the result, then the other projects at the same workspace.
     * </p>
     * <p>
     * Otherwise, just loop every projects at the current workspace and combine the parsed source containers directly.
     * </p>
     * @param projectName
     *                  the project name.
     * @return the possible source container list.
     */
    public static ISourceContainer[] getSourceContainers(String projectName) {
        Set<ISourceContainer> containers = new LinkedHashSet<>();
        List<IProject> projects = new ArrayList<>();

        // If the project name is specified, firstly compute the source containers from the specified project's
        // classpath entries so that they can be placed in the front of the result.
        IProject targetProject = JdtUtils.getProject(projectName);
        if (targetProject != null) {
            projects.add(targetProject);
        }

        List<IProject> workspaceProjects = Arrays.asList(ResourcesPlugin.getWorkspace().getRoot().getProjects());
        projects.addAll(workspaceProjects);

        Set<IRuntimeClasspathEntry> calculated = new LinkedHashSet<>();

        projects.stream().distinct().map(project -> JdtUtils.getJavaProject(project))
            .filter(javaProject -> javaProject != null && javaProject.exists())
            .forEach(javaProject -> {
                // Add source containers associated with the project's source folders.
                containers.add(new JavaProjectSourceContainer(javaProject));
                // Add source containers associated with the project's runtime classpath entries.
                containers.addAll(Arrays.asList(getSourceContainers(javaProject, calculated)));
            });

        return containers.toArray(new ISourceContainer[0]);
    }

    private static ISourceContainer[] getSourceContainers(IJavaProject project, Set<IRuntimeClasspathEntry> calculated) {
        if (project == null || !project.exists()) {
            return new ISourceContainer[0];
        }

        try {
            IRuntimeClasspathEntry[] unresolved = JavaRuntime.computeUnresolvedRuntimeClasspath(project);
            List<IRuntimeClasspathEntry> resolved = new ArrayList<>();
            for (IRuntimeClasspathEntry entry : unresolved) {
                for (IRuntimeClasspathEntry resolvedEntry : JavaRuntime.resolveRuntimeClasspathEntry(entry, project)) {
                    if (!calculated.contains(resolvedEntry)) {
                        calculated.add(resolvedEntry);
                        resolved.add(resolvedEntry);
                    }
                }
            }
            Set<ISourceContainer> containers = new LinkedHashSet<>();
            containers.addAll(Arrays.asList(
                    JavaRuntime.getSourceContainers(resolved.toArray(new IRuntimeClasspathEntry[0]))));

            // Due to a known jdt java 9 support bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=525840,
            // it would miss some JRE libraries source containers when the debugger is running on JDK9.
            // As a workaround, recompute the possible source containers for JDK9 jrt-fs.jar libraries.
            IRuntimeClasspathEntry jrtFs = resolved.stream().filter(entry -> {
                return entry.getType() == IRuntimeClasspathEntry.ARCHIVE && entry.getPath().lastSegment().equals("jrt-fs.jar");
            }).findFirst().orElse(null);
            if (jrtFs != null && project.isOpen()) {
                IPackageFragmentRoot[] allRoots = project.getPackageFragmentRoots();
                for (IPackageFragmentRoot root : allRoots) {
                    if (root.getPath().equals(jrtFs.getPath()) && isSourceAttachmentEqual(root, jrtFs)) {
                        containers.add(new PackageFragmentRootSourceContainer(root));
                    }
                }
            }

            return containers.toArray(new ISourceContainer[0]);
        } catch (CoreException ex) {
         // do nothing.
        }

        return new ISourceContainer[0];
    }

    private static boolean isSourceAttachmentEqual(IPackageFragmentRoot root, IRuntimeClasspathEntry entry) throws JavaModelException {
        IPath entryPath = entry.getSourceAttachmentPath();
        if (entryPath == null) {
            return true;
        }
        IPath rootPath = root.getSourceAttachmentPath();
        if (rootPath == null) {
            // entry has a source attachment that the package root does not
            return false;
        }
        return rootPath.equals(entryPath);

    }

    /**
     * Given a source name info, search the associated source file or class file from the source container list.
     *
     * @param sourcePath
     *                  the target source name (e.g. com\microsoft\java\debug\xxx.java).
     * @param containers
     *                  the source container list.
     * @return the associated source file or class file.
     */
    public static Object findSourceElement(String sourcePath, ISourceContainer[] containers) {
        if (containers == null) {
            return null;
        }
        for (ISourceContainer container : containers) {
            try {
                Object[] objects = container.findSourceElements(sourcePath);
                if (objects.length > 0 && (objects[0] instanceof IResource || objects[0] instanceof IClassFile)) {
                    return objects[0];
                }
            } catch (CoreException e) {
                // do nothing.
            }
        }
        return null;
    }

    /**
     * Given a stack frame, find the target java project that the associated source file belongs to.
     *
     * @param stackFrame
     *                  the stack frame.
     * @param containers
     *                  the source container list.
     * @return the java project.
     */
    public static IJavaProject findProject(StackFrame stackFrame, ISourceContainer[] containers) {
        Location location = stackFrame.location();
        try {
            Object sourceElement = findSourceElement(location.sourcePath(), containers);
            if (sourceElement instanceof IResource) {
                return JavaCore.create(((IResource) sourceElement).getProject());
            } else if (sourceElement instanceof IClassFile) {
                IJavaProject javaProject = ((IClassFile) sourceElement).getJavaProject();
                if (javaProject != null) {
                    return javaProject;
                }
            }
        } catch (AbsentInformationException e) {
            // When the compiled .class file doesn't contain debug source information, return null.
        }
        return null;
    }

    /**
     * Given a stack frame, get the fully qualified type name that associated with the frame.
     * @param frame the stack frame
     * @return the fully qualified type name
     * @throws DebugException debug exception
     */
    public static String getDeclaringTypeName(StackFrame frame) throws DebugException {
        return getGenericName(StackFrameUtility.getDeclaringType(frame));
    }

    private static String getGenericName(ReferenceType type) throws DebugException {
        if (type instanceof ArrayType) {
            try {
                Type componentType;
                componentType = ((ArrayType) type).componentType();
                if (componentType instanceof ReferenceType) {
                    return getGenericName((ReferenceType) componentType) + "[]"; //$NON-NLS-1$
                }
                return type.name();
            } catch (ClassNotLoadedException e) {
                // we cannot create the generic name using the component type,
                // just try to create one with the information
            }
        }
        String signature = type.signature();
        StringBuffer res = new StringBuffer(getTypeName(signature));
        String genericSignature = type.genericSignature();
        if (genericSignature != null) {
            String[] typeParameters = Signature.getTypeParameters(genericSignature);
            if (typeParameters.length > 0) {
                res.append('<').append(Signature.getTypeVariable(typeParameters[0]));
                for (int i = 1; i < typeParameters.length; i++) {
                    res.append(',').append(Signature.getTypeVariable(typeParameters[i]));
                }
                res.append('>');
            }
        }
        return res.toString();
    }

    private static String getTypeName(String genericTypeSignature) {
        int arrayDimension = 0;
        while (genericTypeSignature.charAt(arrayDimension) == '[') {
            arrayDimension++;
        }
        int parameterStart = genericTypeSignature.indexOf('<');
        StringBuffer name = new StringBuffer();
        if (parameterStart < 0) {
            name.append(genericTypeSignature.substring(arrayDimension + 1, genericTypeSignature.length() - 1)
                    .replace('/', '.'));
        } else {
            if (parameterStart != 0) {
                name.append(genericTypeSignature.substring(arrayDimension + 1, parameterStart).replace('/', '.'));
            }
            try {
                String sig = Signature.toString(genericTypeSignature)
                        .substring(Math.max(parameterStart - 1, 0) - arrayDimension);
                name.append(sig.replace('/', '.'));
            } catch (IllegalArgumentException iae) {
                // do nothing
                name.append(genericTypeSignature);
            }
        }
        for (int i = 0; i < arrayDimension; i++) {
            name.append("[]"); //$NON-NLS-1$
        }
        return name.toString();
    }

    /**
     * Check whether two resources point to the same physical file.
     */
    public static boolean isSameFile(IResource resource1, IResource resource2) {
        if (resource1 == null || resource2 == null) {
            return false;
        }

        if (Objects.equals(resource1, resource2)) {
            return true;
        }

        return Objects.equals(resource1.getLocation(), resource2.getLocation());
    }
}
