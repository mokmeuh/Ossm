package com.ossm.remote.data.repository;

import com.ossm.remote.ble.BleManager;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation"
})
public final class DiagnosticsRepository_Factory implements Factory<DiagnosticsRepository> {
  private final Provider<BleManager> bleManagerProvider;

  public DiagnosticsRepository_Factory(Provider<BleManager> bleManagerProvider) {
    this.bleManagerProvider = bleManagerProvider;
  }

  @Override
  public DiagnosticsRepository get() {
    return newInstance(bleManagerProvider.get());
  }

  public static DiagnosticsRepository_Factory create(Provider<BleManager> bleManagerProvider) {
    return new DiagnosticsRepository_Factory(bleManagerProvider);
  }

  public static DiagnosticsRepository newInstance(BleManager bleManager) {
    return new DiagnosticsRepository(bleManager);
  }
}
