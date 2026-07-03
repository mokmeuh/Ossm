package com.ossm.remote.viewmodel;

import com.ossm.remote.ble.BleManager;
import com.ossm.remote.data.repository.DiagnosticsRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
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
public final class DiagnosticsViewModel_Factory implements Factory<DiagnosticsViewModel> {
  private final Provider<DiagnosticsRepository> repositoryProvider;

  private final Provider<BleManager> bleManagerProvider;

  public DiagnosticsViewModel_Factory(Provider<DiagnosticsRepository> repositoryProvider,
      Provider<BleManager> bleManagerProvider) {
    this.repositoryProvider = repositoryProvider;
    this.bleManagerProvider = bleManagerProvider;
  }

  @Override
  public DiagnosticsViewModel get() {
    return newInstance(repositoryProvider.get(), bleManagerProvider.get());
  }

  public static DiagnosticsViewModel_Factory create(
      Provider<DiagnosticsRepository> repositoryProvider, Provider<BleManager> bleManagerProvider) {
    return new DiagnosticsViewModel_Factory(repositoryProvider, bleManagerProvider);
  }

  public static DiagnosticsViewModel newInstance(DiagnosticsRepository repository,
      BleManager bleManager) {
    return new DiagnosticsViewModel(repository, bleManager);
  }
}
