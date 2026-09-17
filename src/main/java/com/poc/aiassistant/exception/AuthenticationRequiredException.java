package com.poc.aiassistant.exception;

public class AuthenticationRequiredException extends RuntimeException {

    public AuthenticationRequiredException() {
        super("Microsoft authentication is required.");
    }

    public AuthenticationRequiredException(String message) {
        super(message);
    }
}