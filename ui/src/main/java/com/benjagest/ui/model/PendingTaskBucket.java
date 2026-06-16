package com.benjagest.ui.model;

/** Un grupo de tareas pendientes (AVISOS): tipo, nº y severidad. */
public record PendingTaskBucket(String type, int count, String severity) {}
