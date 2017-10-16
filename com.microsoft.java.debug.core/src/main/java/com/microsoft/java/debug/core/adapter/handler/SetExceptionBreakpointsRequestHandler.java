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

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.ArrayUtils;

import com.microsoft.java.debug.core.adapter.AdapterUtils;
import com.microsoft.java.debug.core.adapter.ErrorCode;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.IDebugRequestHandler;
import com.microsoft.java.debug.core.protocol.Messages.Response;
import com.microsoft.java.debug.core.protocol.Requests.Arguments;
import com.microsoft.java.debug.core.protocol.Requests.Command;
import com.microsoft.java.debug.core.protocol.Requests.SetExceptionBreakpointsArguments;
import com.microsoft.java.debug.core.protocol.Types;

public class SetExceptionBreakpointsRequestHandler implements IDebugRequestHandler {

    @Override
    public List<Command> getTargetCommands() {
        return Arrays.asList(Command.SETEXCEPTIONBREAKPOINTS);
    }

    @Override
    public void handle(Command command, Arguments arguments, Response response, IDebugAdapterContext context) {
        if (context.getDebugSession() == null) {
            AdapterUtils.setErrorResponse(response, ErrorCode.EMPTY_DEBUG_SESSION, "Empty debug session.");
            return;
        }

        String[] filters = ((SetExceptionBreakpointsArguments) arguments).filters;
        try {
            boolean notifyCaught = ArrayUtils.contains(filters, Types.ExceptionBreakpointFilter.CAUGHT_EXCEPTION_FILTER_NAME);
            boolean notifyUncaught = ArrayUtils.contains(filters, Types.ExceptionBreakpointFilter.UNCAUGHT_EXCEPTION_FILTER_NAME);

            context.getDebugSession().setExceptionBreakpoints(notifyCaught, notifyUncaught);
        } catch (Exception ex) {
            AdapterUtils.setErrorResponse(response, ErrorCode.SET_EXCEPTIONBREAKPOINT_FAILURE,
                    String.format("Failed to setExceptionBreakpoints. Reason: '%s'", ex.toString()));
        }
    }

}
