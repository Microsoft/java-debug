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

import java.util.Map;

import com.microsoft.java.debug.core.adapter.IVirtualMachineManagerProvider;
import com.sun.jdi.VirtualMachineManager;

public class JdtVirtualMachineManagerProvider implements IVirtualMachineManagerProvider {
    private static VirtualMachineManager vmManager = null;

    @Override
    public synchronized VirtualMachineManager getVirtualMachineManager() {
        if (vmManager == null) {
            vmManager = new AdvancedVirtualMachineManager();
        }
        return vmManager;
    }

    @Override
    public void initialize(Map<String, Object> props) {
    }
}
