package ru.ravel.ultunnel.aidl;

import ru.ravel.ultunnel.aidl.IServiceCallback;

interface IService {
  int getStatus();
  void registerCallback(in IServiceCallback callback);
  oneway void unregisterCallback(in IServiceCallback callback);
}