package com.grocery.ai.tool;

/** ToolGuard step 3: the model's arguments break the tool's types, ranges or lengths (BLOCKED INVALID_ARGUMENTS). */
public class InvalidArgumentsException extends RuntimeException {

    public InvalidArgumentsException(String message) {
        super(message);
    }
}
