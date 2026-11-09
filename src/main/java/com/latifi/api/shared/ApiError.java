package com.latifi.api.shared;

/** Body of every error response, as declared by the ApiError schema of the contract. */
public record ApiError(int status, String code, String message) {}
