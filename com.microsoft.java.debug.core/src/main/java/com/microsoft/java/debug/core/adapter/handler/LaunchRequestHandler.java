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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;

import com.microsoft.java.debug.core.Configuration;
import com.microsoft.java.debug.core.DebugUtility;
import com.microsoft.java.debug.core.IDebugSession;
import com.microsoft.java.debug.core.adapter.AdapterUtils;
import com.microsoft.java.debug.core.adapter.Constants;
import com.microsoft.java.debug.core.adapter.ErrorCode;
import com.microsoft.java.debug.core.adapter.Events;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.IDebugRequestHandler;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.IVirtualMachineManagerProvider;
import com.microsoft.java.debug.core.adapter.Messages.Response;
import com.microsoft.java.debug.core.adapter.ProcessConsole;
import com.microsoft.java.debug.core.adapter.Requests.Arguments;
import com.microsoft.java.debug.core.adapter.Requests.Command;
import com.microsoft.java.debug.core.adapter.Requests.LaunchArguments;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.VMStartException;

public class LaunchRequestHandler implements IDebugRequestHandler {
    private static final Logger logger = Logger.getLogger(Configuration.LOGGER_NAME);

    @Override
    public List<Command> getTargetCommands() {
        return Arrays.asList(Command.LAUNCH);
    }

    @Override
    public void handle(Command command, Arguments arguments, Response response, IDebugAdapterContext context) {
        LaunchArguments launchArguments = (LaunchArguments) arguments;
        if (StringUtils.isBlank(launchArguments.mainClass) || launchArguments.classPaths == null
                || launchArguments.classPaths.length == 0) {
            AdapterUtils.setErrorResponse(response, ErrorCode.ARGUMENT_MISSING,
                    String.format("Failed to launch debuggee VM. Missing mainClass or classPath options in launch configuration"));
            return;
        }

        context.setAttached(false);
        context.setSourcePaths(launchArguments.sourcePaths);
        context.setDebuggeeEncoding(StandardCharsets.UTF_8); // Use UTF-8 as debuggee's default encoding format.

        IVirtualMachineManagerProvider vmProvider = context.getProvider(IVirtualMachineManagerProvider.class);
        ISourceLookUpProvider sourceProvider = context.getProvider(ISourceLookUpProvider.class);
        Map<String, Object> options = sourceProvider.getDefaultOptions();
        options.put(Constants.DEBUGGEE_ENCODING, context.getDebuggeeEncoding());
        if (launchArguments.projectName != null) {
            options.put(Constants.PROJECTNAME, launchArguments.projectName);
        }
        sourceProvider.initialize(options);

        try {
            logger.info(String.format("Trying to launch Java Program with options \"%s -cp %s %s %s\" .",
                    launchArguments.vmArgs, launchArguments.classPaths, launchArguments.mainClass, launchArguments.args));
            IDebugSession debugSession = DebugUtility.launch(vmProvider.getVirtualMachineManager(),
                    launchArguments.mainClass, launchArguments.args, launchArguments.vmArgs, Arrays.asList(launchArguments.classPaths));
            context.setDebugSession(debugSession);
            logger.info("Launching debuggee VM succeeded.");

            ProcessConsole debuggeeConsole = new ProcessConsole(debugSession.process(), "Debuggee", context.getDebuggeeEncoding());
            debuggeeConsole.onStdout((output) -> {
                // When DA receives a new OutputEvent, it just shows that on Debug Console and doesn't affect the DA's dispatching workflow.
                // That means the debugger can send OutputEvent to DA at any time.
                context.sendEvent(Events.OutputEvent.createStdoutOutput(output));
            });

            debuggeeConsole.onStderr((err) -> {
                context.sendEvent(Events.OutputEvent.createStderrOutput(err));
            });
            debuggeeConsole.start();
        } catch (IOException | IllegalConnectorArgumentsException | VMStartException e) {
            AdapterUtils.setErrorResponse(response, ErrorCode.LAUNCH_FAILURE,
                    String.format("Failed to launch debuggee VM. Reason: %s", e.toString()));
        }
    }
}
