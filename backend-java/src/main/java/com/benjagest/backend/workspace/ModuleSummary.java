package com.benjagest.backend.workspace;

import java.util.List;

public record ModuleSummary(String module, String title, List<ModuleRecord> records) {
}
