/*******************************************************************************
* Copyright (c) 2018 Microsoft Corporation and others.
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import com.microsoft.java.debug.core.Configuration;
import com.microsoft.java.debug.core.DebugSettings;
import com.microsoft.java.debug.core.DebugUtility;
import com.microsoft.java.debug.core.adapter.AdapterUtils;
import com.microsoft.java.debug.core.adapter.ErrorCode;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.IDebugRequestHandler;
import com.microsoft.java.debug.core.adapter.LaunchMode;
import com.microsoft.java.debug.core.protocol.Messages.Response;
import com.microsoft.java.debug.core.protocol.Requests.Arguments;
import com.microsoft.java.debug.core.protocol.Requests.CONSOLE;
import com.microsoft.java.debug.core.protocol.Requests.Command;
import com.microsoft.java.debug.core.protocol.Requests.LaunchArguments;

public class LaunchRequestHandler implements IDebugRequestHandler {
    protected static final Logger logger = Logger.getLogger(Configuration.LOGGER_NAME);
    protected static final long RUNINTERMINAL_TIMEOUT = 10 * 1000;
    protected ILaunchDelegate activeLaunchHandler;

    @Override
    public List<Command> getTargetCommands() {
        return Arrays.asList(Command.LAUNCH);
    }

    @Override
    public CompletableFuture<Response> handle(Command command, Arguments arguments, Response response, IDebugAdapterContext context) {
        LaunchArguments launchArguments = (LaunchArguments) arguments;
        activeLaunchHandler = launchArguments.noDebug ? new LaunchWithoutDebuggingDelegate() : new LaunchWithDebuggingDelegate();
        return handleLaunchCommand(arguments, response, context);
    }

    protected CompletableFuture<Response> handleLaunchCommand(Arguments arguments, Response response, IDebugAdapterContext context) {
        LaunchArguments launchArguments = (LaunchArguments) arguments;
        // validation
        if (StringUtils.isBlank(launchArguments.mainClass)
                || ArrayUtils.isEmpty(launchArguments.modulePaths) && ArrayUtils.isEmpty(launchArguments.classPaths)) {
            throw AdapterUtils.createCompletionException(
                "Failed to launch debuggee VM. Missing mainClass or modulePaths/classPaths options in launch configuration.",
                ErrorCode.ARGUMENT_MISSING);
        }
        if (StringUtils.isBlank(launchArguments.encoding)) {
            context.setDebuggeeEncoding(StandardCharsets.UTF_8);
        } else {
            if (!Charset.isSupported(launchArguments.encoding)) {
                throw AdapterUtils.createCompletionException(
                    "Failed to launch debuggee VM. 'encoding' options in the launch configuration is not recognized.",
                    ErrorCode.INVALID_ENCODING);
            }
            context.setDebuggeeEncoding(Charset.forName(launchArguments.encoding));
        }

        if (StringUtils.isBlank(launchArguments.vmArgs)) {
            launchArguments.vmArgs = String.format("-Dfile.encoding=%s", context.getDebuggeeEncoding().name());
        } else {
            // if vmArgs already has the file.encoding settings, duplicate options for jvm will not cause an error, the right most value wins
            launchArguments.vmArgs = String.format("%s -Dfile.encoding=%s", launchArguments.vmArgs, context.getDebuggeeEncoding().name());
        }
        context.setLaunchMode(launchArguments.noDebug ? LaunchMode.NO_DEBUG : LaunchMode.DEBUG);

        activeLaunchHandler.preLaunch(launchArguments, context);

        return launch(launchArguments, response, context).thenCompose(res -> {
            if (res.success) {
                activeLaunchHandler.postLaunch(launchArguments, context);
            }
            return CompletableFuture.completedFuture(res);
        });
    }

    protected static String[] constructLaunchCommands(LaunchArguments launchArguments, boolean serverMode, String address) {
        String slash = System.getProperty("file.separator");

        List<String> launchCmds = new ArrayList<>();
        final String javaHome = StringUtils.isNotEmpty(DebugSettings.getCurrent().javaHome) ? DebugSettings.getCurrent().javaHome
                : System.getProperty("java.home");
        launchCmds.add(javaHome + slash + "bin" + slash + "java");
        if (StringUtils.isNotEmpty(address)) {
            launchCmds.add(String.format("-agentlib:jdwp=transport=dt_socket,server=%s,suspend=y,address=%s", serverMode ? "y" : "n", address));
        }
        if (StringUtils.isNotBlank(launchArguments.vmArgs)) {
            launchCmds.addAll(DebugUtility.parseArguments(launchArguments.vmArgs));
        }
        if (ArrayUtils.isNotEmpty(launchArguments.modulePaths)) {
            launchCmds.add("--module-path");
            launchCmds.add(String.join(File.pathSeparator, launchArguments.modulePaths));
        }
        if (ArrayUtils.isNotEmpty(launchArguments.classPaths)) {
            launchCmds.add("-cp");
            launchCmds.add(String.join(File.pathSeparator, launchArguments.classPaths));
        }
        // For java 9 project, should specify "-m $MainClass".
        String[] mainClasses = launchArguments.mainClass.split("/");
        if (ArrayUtils.isNotEmpty(launchArguments.modulePaths) || mainClasses.length == 2) {
            launchCmds.add("-m");
        }
        launchCmds.add(launchArguments.mainClass);
        if (StringUtils.isNotBlank(launchArguments.args)) {
            launchCmds.addAll(DebugUtility.parseArguments(launchArguments.args));
        }
        return launchCmds.toArray(new String[0]);
    }

    protected CompletableFuture<Response> launch(LaunchArguments launchArguments, Response response, IDebugAdapterContext context) {
        logger.info("Trying to launch Java Program with options:\n" + String.format("main-class: %s\n", launchArguments.mainClass)
                + String.format("args: %s\n", launchArguments.args)
                + String.format("module-path: %s\n", StringUtils.join(launchArguments.modulePaths, File.pathSeparator))
                + String.format("class-path: %s\n", StringUtils.join(launchArguments.classPaths, File.pathSeparator))
                + String.format("vmArgs: %s", launchArguments.vmArgs));

        if (context.supportsRunInTerminalRequest()
                && (launchArguments.console == CONSOLE.integratedTerminal || launchArguments.console == CONSOLE.externalTerminal)) {
            return activeLaunchHandler.launchInTerminal(launchArguments, response, context);
        } else {
            return activeLaunchHandler.launchInternally(launchArguments, response, context);
        }
    }

    protected static String[] constructEnvironmentVariables(LaunchArguments launchArguments) {
        String[] envVars = null;
        if (launchArguments.env != null && !launchArguments.env.isEmpty()) {
            Map<String, String> environment = new HashMap<>(System.getenv());
            List<String> duplicated = new ArrayList<>();
            for (Entry<String, String> entry : launchArguments.env.entrySet()) {
                if (environment.containsKey(entry.getKey())) {
                    duplicated.add(entry.getKey());
                }
                environment.put(entry.getKey(), entry.getValue());
            }
            // For duplicated variables, show a warning message.
            if (!duplicated.isEmpty()) {
                logger.warning(String.format("There are duplicated environment variables. The values specified in launch.json will be used. "
                        + "Here are the duplicated entries: %s.", String.join(",", duplicated)));
            }

            envVars = new String[environment.size()];
            int i = 0;
            for (Entry<String, String> entry : environment.entrySet()) {
                envVars[i++] = entry.getKey() + "=" + entry.getValue();
            }
        }
        return envVars;
    }

    public static String parseMainClassWithoutModuleName(String mainClass) {
        int index = mainClass.indexOf('/');
        return mainClass.substring(index + 1);
    }
}
