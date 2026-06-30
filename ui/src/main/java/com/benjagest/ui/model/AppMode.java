package com.benjagest.ui.model;

/**
 * Modo de la aplicacion: ASESORIA (gestiona una cartera de clientes) o
 * EMPRESA/autonomo (solo su propia gestion). Extraido del God Object en UIR-2.
 */
public enum AppMode {
    ADVISORY("ADVISORY", "mode.advisory", "mode.advisory.eyebrow", "mode.advisory.description", "fas-briefcase"),
    BUSINESS("BUSINESS", "mode.business", "mode.business.eyebrow", "mode.business.description", "fas-building");

    private final String apiValue;
    private final String labelKey;
    private final String eyebrowKey;
    private final String descriptionKey;
    private final String icon;

    AppMode(String apiValue, String labelKey, String eyebrowKey, String descriptionKey, String icon) {
        this.apiValue = apiValue;
        this.labelKey = labelKey;
        this.eyebrowKey = eyebrowKey;
        this.descriptionKey = descriptionKey;
        this.icon = icon;
    }

    public String apiValue() {
        return apiValue;
    }

    public String labelKey() {
        return labelKey;
    }

    public String eyebrowKey() {
        return eyebrowKey;
    }

    public String descriptionKey() {
        return descriptionKey;
    }

    public String icon() {
        return icon;
    }

    public static AppMode from(String value) {
        if ("BUSINESS".equalsIgnoreCase(value)) {
            return BUSINESS;
        }
        return ADVISORY;
    }
}
