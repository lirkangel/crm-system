package com.crm.dental.previsit;

import jakarta.validation.constraints.NotNull;

/** Body of the apply call: the version of the form staff reviewed ({@code PrevisitView.formVersion}). */
public record ApplyRequest(@NotNull Long formVersion) {

    @Override
    public String toString() {
        return "ApplyRequest[redacted]";
    }
}
