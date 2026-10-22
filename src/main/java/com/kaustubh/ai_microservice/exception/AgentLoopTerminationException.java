package com.kaustubh.ai_microservice.exception;

public class AgentLoopTerminationException extends RuntimeException {

    public AgentLoopTerminationException(String message) {
        super(message);
    }

    public AgentLoopTerminationException(String message, Throwable cause) {
        super(message, cause);
    }
}