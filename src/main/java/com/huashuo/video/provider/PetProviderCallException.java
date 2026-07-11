package com.huashuo.video.provider;

import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.model.ProviderFailureDiagnostics;

/**
 * Carries provider diagnostics without changing the task failure policy.
 */
public class PetProviderCallException extends BusinessException {

    private final ProviderFailureDiagnostics diagnostics;

    public PetProviderCallException(int code, String message, ProviderFailureDiagnostics diagnostics,
                                    Throwable cause) {
        super(code, message);
        this.diagnostics = diagnostics;
        if (cause != null) {
            initCause(cause);
        }
    }

    public ProviderFailureDiagnostics getDiagnostics() {
        return diagnostics;
    }
}
