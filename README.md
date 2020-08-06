# Java Debug Server for Visual Studio Code

## Overview

The Java Debug Server is an implementation of Visual Studio Code (VSCode) Debug Protocol. It can be used in Visual Studio Code to debug Java programs.

## Features
- Launch/Attach
- Breakpoints
- Exceptions
- Pause & Continue
- Step In/Out/Over
- Variables
- Callstacks
- Threads
- Debug console

## Background

The Java Debug Server is the bridge between VSCode and JVM. The implementation is based on JDI ([Java Debug Interface](https://docs.oracle.com/javase/7/docs/jdk/api/jpda/jdi/)). It works with [Eclipse JDT Language Server](https://github.com/vscjavaci/eclipse.jdt.ls) as an add-on to provide debug functionalities.

## Repository Structure

- com.microsoft.java.debug.core - the core logic of the debug server
- com.microsoft.java.debug.plugin - wraps the debug server into an Eclipse plugin to work with Eclipse JDT Language Server

## Installation

### Requirements
- JDK (version 11 or later)

### Windows:
```
mvnw.cmd clean install
```
### Linux and macOS:
```
./mvnw clean install
```


## Usage with eclipse.jdt.ls

To use `java-debug` as a [jdt.ls](https://github.com/eclipse/eclipse.jdt.ls) plugin, an [LSP client](https://langserver.org/) has to launch [jdt.ls](https://github.com/eclipse/eclipse.jdt.ls) with `initializationOptions` that contain the path to the built `java-debug` jar within a `bundles` array:


```
{
    "initializationOptions": {
        "bundles": [
            "path/to/microsoft/java-debug/com.microsoft.java.debug.plugin/target/com.microsoft.java.debug.plugin-<version>.jar"
        ]
    }
}
```

Editor extensions like [vscode-java](https://github.com/redhat-developer/vscode-java) take care of this.


Once `eclipse.jdt.ls` launched, the client can send a [Command](https://microsoft.github.io/language-server-protocol/specifications/specification-current/#command) to the server to start a debug session:

```
{
  "command": "vscode.java.startDebugSession"
}
```

The response to this request will contain a port number on which the debug adapter is listening, and to which a client implementing the debug-adapter protocol can connect to.


License
-------
EPL 1.0, See [LICENSE](LICENSE.txt) file.
