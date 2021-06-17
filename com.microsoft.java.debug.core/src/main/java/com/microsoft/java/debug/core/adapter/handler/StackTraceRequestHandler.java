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

package com.microsoft.java.debug.core.adapter.handler;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.microsoft.java.debug.core.DebugUtility;
import com.microsoft.java.debug.core.adapter.AdapterUtils;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.IDebugRequestHandler;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.formatter.SimpleTypeFormatter;
import com.microsoft.java.debug.core.adapter.variables.StackFrameReference;
import com.microsoft.java.debug.core.protocol.Messages.Response;
import com.microsoft.java.debug.core.protocol.Requests.Arguments;
import com.microsoft.java.debug.core.protocol.Requests.Command;
import com.microsoft.java.debug.core.protocol.Requests.StackTraceArguments;
import com.microsoft.java.debug.core.protocol.Responses;
import com.microsoft.java.debug.core.protocol.Types;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;

public class StackTraceRequestHandler implements IDebugRequestHandler {

    @Override
    public List<Command> getTargetCommands() {
        return Arrays.asList(Command.STACKTRACE);
    }

    @Override
    public CompletableFuture<Response> handle(Command command, Arguments arguments, Response response, IDebugAdapterContext context) {
        StackTraceArguments stacktraceArgs = (StackTraceArguments) arguments;
        List<Types.StackFrame> result = new ArrayList<>();
        if (stacktraceArgs.startFrame < 0 || stacktraceArgs.levels < 0) {
            response.body = new Responses.StackTraceResponseBody(result, 0);
            return CompletableFuture.completedFuture(response);
        }
        ThreadReference thread = DebugUtility.getThread(context.getDebugSession(), stacktraceArgs.threadId);
        int totalFrames = 0;
        if (thread != null) {
            try {
                totalFrames = thread.frameCount();
                if (totalFrames <= stacktraceArgs.startFrame) {
                    response.body = new Responses.StackTraceResponseBody(result, totalFrames);
                    return CompletableFuture.completedFuture(response);
                }
                StackFrame[] frames = context.getStackFrameManager().reloadStackFrames(thread);

                int count = stacktraceArgs.levels == 0 ? totalFrames - stacktraceArgs.startFrame
                        : Math.min(totalFrames - stacktraceArgs.startFrame, stacktraceArgs.levels);
                for (int i = stacktraceArgs.startFrame; i < frames.length && count-- > 0; i++) {
                    StackFrameReference stackframe = new StackFrameReference(thread, i);
                    int frameId = context.getRecyclableIdPool().addObject(thread.uniqueID(), stackframe);
                    result.add(convertDebuggerStackFrameToClient(frames[i], frameId, context));
                }
            } catch (IncompatibleThreadStateException | IndexOutOfBoundsException | URISyntaxException
                    | AbsentInformationException | ObjectCollectedException e) {
                // when error happens, the possible reason is:
                // 1. the vscode has wrong parameter/wrong uri
                // 2. the thread actually terminates
                // TODO: should record a error log here.
            }
        }
        response.body = new Responses.StackTraceResponseBody(result, totalFrames);
        return CompletableFuture.completedFuture(response);
    }

    private Types.StackFrame convertDebuggerStackFrameToClient(StackFrame stackFrame, int frameId, IDebugAdapterContext context)
            throws URISyntaxException, AbsentInformationException {
        Location location = stackFrame.location();
        Method method = location.method();
        Types.Source clientSource = this.convertDebuggerSourceToClient(location, context);
        String methodName = formatMethodName(method, true, true);
        int lineNumber = AdapterUtils.convertLineNumber(location.lineNumber(), context.isDebuggerLinesStartAt1(), context.isClientLinesStartAt1());
        // Line number returns -1 if the information is not available; specifically, always returns -1 for native methods.
        if (lineNumber < 0) {
            if (method.isNative()) {
                // For native method, display a tip text "native method" in the Call Stack View.
                methodName += "[native method]";
            } else {
                // For other unavailable method, such as lambda expression's built-in methods run/accept/apply,
                // display "Unknown Source" in the Call Stack View.
                clientSource = null;
            }
        }
        return new Types.StackFrame(frameId, methodName, clientSource, lineNumber, context.isClientColumnsStartAt1() ? 1 : 0);
    }

    private Types.Source convertDebuggerSourceToClient(Location location, IDebugAdapterContext context) throws URISyntaxException {
        final String fullyQualifiedName = location.declaringType().name();
        String sourceName = "";
        String relativeSourcePath = "";
        try {
            // When the .class file doesn't contain source information in meta data,
            // invoking Location#sourceName() would throw AbsentInformationException.
            sourceName = location.sourceName();
            relativeSourcePath = location.sourcePath();
        } catch (AbsentInformationException e) {
            String enclosingType = AdapterUtils.parseEnclosingType(fullyQualifiedName);
            sourceName = enclosingType.substring(enclosingType.lastIndexOf('.') + 1) + ".java";
            relativeSourcePath = enclosingType.replace('.', File.separatorChar) + ".java";
        }

        return convertDebuggerSourceToClient(fullyQualifiedName, sourceName, relativeSourcePath, context);
    }

    /**
     * Find the source mapping for the specified source file name.
     */
    public static Types.Source convertDebuggerSourceToClient(String fullyQualifiedName, String sourceName, String relativeSourcePath,
            IDebugAdapterContext context) throws URISyntaxException {
        // use a lru cache for better performance
        String uri = context.getSourceLookupCache().computeIfAbsent(fullyQualifiedName, key -> {
            String fromProvider = context.getProvider(ISourceLookUpProvider.class).getSourceFileURI(key, relativeSourcePath);
            // avoid return null which will cause the compute function executed again
            return StringUtils.isBlank(fromProvider) ? "" : fromProvider;
        });

        if (!StringUtils.isBlank(uri)) {
            // The Source.path could be a file system path or uri string.
            if (uri.startsWith("file:")) {
                String clientPath = AdapterUtils.convertPath(uri, context.isDebuggerPathsAreUri(), context.isClientPathsAreUri());
                return new Types.Source(sourceName, clientPath, context.createSourceReference(uri));
            } else {
                // If the debugger returns uri in the Source.path for the StackTrace response, VSCode client will try to find a TextDocumentContentProvider
                // to render the contents.
                // Language Support for Java by Red Hat extension has already registered a jdt TextDocumentContentProvider to parse the jdt-based uri.
                // The jdt uri looks like 'jdt://contents/rt.jar/java.io/PrintStream.class?=1.helloworld/%5C/usr%5C/lib%5C/jvm%5C/java-8-oracle%5C/jre%5C/
                // lib%5C/rt.jar%3Cjava.io(PrintStream.class'.
                return new Types.Source(sourceName, uri, context.createSourceReference(uri));
            }
        } else {
            // If the source lookup engine cannot find the source file, then lookup it in the source directories specified by user.
            String absoluteSourcepath = AdapterUtils.sourceLookup(context.getSourcePaths(), relativeSourcePath);
            if (absoluteSourcepath != null) {
                return new Types.Source(sourceName, absoluteSourcepath, context.createSourceReference(uri));
            } else {
                return null;
            }
        }
    }

    private String formatMethodName(Method method, boolean showContextClass, boolean showParameter) {
        StringBuilder formattedName = new StringBuilder();
        if (showContextClass) {
            String fullyQualifiedClassName = method.declaringType().name();
            formattedName.append(SimpleTypeFormatter.trimTypeName(fullyQualifiedClassName));
            formattedName.append(".");
        }
        formattedName.append(method.name());
        if (showParameter) {
            List<String> argumentTypeNames = method.argumentTypeNames().stream().map(SimpleTypeFormatter::trimTypeName).collect(Collectors.toList());
            formattedName.append("(");
            formattedName.append(String.join(",", argumentTypeNames));
            formattedName.append(")");
        }
        return formattedName.toString();
    }
}
