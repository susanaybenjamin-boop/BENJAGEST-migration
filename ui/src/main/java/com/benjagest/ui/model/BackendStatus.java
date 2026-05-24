package com.benjagest.ui.model;

public record BackendStatus(boolean reachable, int httpStatus, String message) {
}
