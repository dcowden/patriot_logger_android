package com.patriotlogger.logger.data;

// Optional: For callbacks if you need to signal completion of async writes
public interface RepositoryCallback<T> {
    void onSuccess(T result);
    void onError(Exception e);
}
