package com.microsoft.java.debug.plugin.internal.eval;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.ISourceLocator;
import org.eclipse.debug.core.sourcelookup.AbstractSourceLookupDirector;
import org.eclipse.debug.core.sourcelookup.containers.ProjectSourceContainer;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.debug.eval.ICompiledExpression;
import org.eclipse.jdt.internal.debug.core.model.JDIDebugTarget;
import org.eclipse.jdt.internal.debug.core.model.JDIStackFrame;
import org.eclipse.jdt.internal.debug.core.model.JDIThread;
import org.eclipse.jdt.internal.debug.eval.ast.engine.ASTEvaluationEngine;
import org.eclipse.jdt.internal.launching.JavaSourceLookupDirector;

import com.microsoft.java.debug.core.Configuration;
import com.microsoft.java.debug.core.adapter.IEvaluationListener;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

public class JdtEvaluationProvider implements IEvaluationProvider {
    private IJavaProject project;
    private ILaunch launch;
    private JDIDebugTarget debugTarget;
    private Map<ThreadReference, JDIThread> threadMap = new HashMap<>();
    private static final Logger logger = Logger.getLogger(Configuration.LOGGER_NAME);

    @Override
    public void eval(String projectName, String code, StackFrame sf, IEvaluationListener listener) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        if (project == null) {
            for (IProject proj : root.getProjects()) {
                try {
                    if (proj.isNatureEnabled("org.eclipse.jdt.core.javanature") && proj.getName().equals(projectName)) {
                        project = JavaCore.create(proj);
                        break;
                    }
                } catch (CoreException e) {
                    e.printStackTrace();
                }
            }
        }

        if (project == null) {
            throw new IllegalStateException("Project " + projectName + " cannot be found.");
        }

        if (launch == null) {
            launch = createLaunch(project);
        }
        ThreadReference thread = sf.thread();
        if (debugTarget == null) {
            debugTarget = new JDIDebugTarget(launch, thread.virtualMachine(), "", false, false, null, false) {
                @Override
                protected synchronized void initialize() {

                }
            };
        }
        try {
            JDIThread JDIthread =  getJDIThread(thread);

            synchronized (JDIthread) {
                if (JDIthread.isPerformingEvaluation()) {
                    JDIthread.wait();
                }

//                int i = JDIthread.getStackFrames().length;
//                logger.severe("start to eval " + code + " " + i);
//                if (JDIthread.getStackFrames().length <= depth ) {
//                    logger.severe("Invalid depth for evaulation " + i);
//                    listener.evaluationComplete(null, new UnsupportedOperationException("Invalid depth for evaulation."));
//                    return;
//                }
                ASTEvaluationEngine engine = new ASTEvaluationEngine(project, debugTarget);
                JDIStackFrame stackframe = createStackFrame(sf);

                ICompiledExpression ie = engine.getCompiledExpression(code, stackframe);
                engine.evaluateExpression(ie, stackframe, evaluateResult -> {
                    synchronized (JDIthread) {
                        JDIthread.notify();
                    }
                    logger.severe("end to eval " + code + " ");
                    if (evaluateResult == null || evaluateResult.hasErrors()) {
                        listener.evaluationComplete(null, new RuntimeException(StringUtils.join(evaluateResult.getErrorMessages())));
                    } else {
                        //((JDIValue)evaluateResult.getValue()).getUnderlyingValue();

                        try {
                            Value value = (Value)FieldUtils.readField(evaluateResult.getValue(), "fValue", true);
                            listener.evaluationComplete(value, null);
                        } catch (IllegalArgumentException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        } catch (IllegalAccessException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }

                        //listener.evaluationComplete((Value)evaluateResult.getValue(), null);
                    }
                }, 0, false);
            }

        } catch (CoreException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private JDIStackFrame createStackFrame(StackFrame sf) {
        return new JDIStackFrame(getJDIThread(sf.thread()), sf, 0);
    }

    private JDIThread getJDIThread(ThreadReference thread) {
        synchronized (threadMap) {
            return threadMap.computeIfAbsent(thread, threadKey -> {
                try {
                    JDIThread newThread = new JDIThread(debugTarget, thread);
//                    newThread.computeStackFrames();
                    threadMap.put(thread, newThread);
                    return newThread;
                } catch(Exception ex) {
                    return null;
                }
            });
        }

    }

    @Override
    public boolean isInEvaluation(ThreadReference thread) {
        return debugTarget != null && getJDIThread(thread).isPerformingEvaluation();
    }

    private static ILaunch createLaunch(IJavaProject project) {
        return new ILaunch() {

            private AbstractSourceLookupDirector locator;
            @Override
            public boolean canTerminate() {

                return false;
            }

            @Override
            public boolean isTerminated() {

                return false;
            }

            @Override
            public void terminate() throws DebugException {

            }

            @Override
            public <T> T getAdapter(Class<T> arg0) {

                return null;
            }

            @Override
            public void addDebugTarget(IDebugTarget arg0) {

            }

            @Override
            public void addProcess(IProcess arg0) {

            }

            @Override
            public String getAttribute(String arg0) {

                return null;
            }

            @Override
            public Object[] getChildren() {

                return null;
            }

            @Override
            public IDebugTarget getDebugTarget() {

                return null;
            }

            @Override
            public IDebugTarget[] getDebugTargets() {

                return null;
            }

            @Override
            public ILaunchConfiguration getLaunchConfiguration() {

                return null;
            }

            @Override
            public String getLaunchMode() {

                return null;
            }

            @Override
            public IProcess[] getProcesses() {

                return null;
            }

            @Override
            public ISourceLocator getSourceLocator() {
                if (locator != null) {
                    return locator;
                }
                locator = new JavaSourceLookupDirector();

                try {
                    locator.setSourceContainers(new ProjectSourceContainer(project.getProject(), true).getSourceContainers());
                } catch (CoreException e) {
                    e.printStackTrace();
                }
                locator.initializeParticipants();
                return locator;
            }

            @Override
            public boolean hasChildren() {

                return false;
            }

            @Override
            public void removeDebugTarget(IDebugTarget arg0) {

            }

            @Override
            public void removeProcess(IProcess arg0) {

            }

            @Override
            public void setAttribute(String arg0, String arg1) {

            }

            @Override
            public void setSourceLocator(ISourceLocator arg0) {

            }

        };
    }



}