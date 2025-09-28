package com.patriotlogger.logger.data;

public interface RepositoryVoidCallback {
    void onSuccess();
    void onError(Exception e);
}
