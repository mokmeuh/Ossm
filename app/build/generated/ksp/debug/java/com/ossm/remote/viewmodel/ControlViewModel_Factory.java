package com.ossm.remote.viewmodel;

import com.ossm.remote.ble.BleManager;
import com.ossm.remote.data.repository.ControlSafetySettingsRepository;
import com.ossm.remote.data.repository.UserHabitsRepository;
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
public final class ControlViewModel_Factory implements Factory<ControlViewModel> {
  private final Provider<BleManager> bleManagerProvider;

  private final Provider<ControlSafetySettingsRepository> safetySettingsRepositoryProvider;

  private final Provider<UserHabitsRepository> userHabitsRepositoryProvider;

  public ControlViewModel_Factory(Provider<BleManager> bleManagerProvider,
      Provider<ControlSafetySettingsRepository> safetySettingsRepositoryProvider,
      Provider<UserHabitsRepository> userHabitsRepositoryProvider) {
    this.bleManagerProvider = bleManagerProvider;
    this.safetySettingsRepositoryProvider = safetySettingsRepositoryProvider;
    this.userHabitsRepositoryProvider = userHabitsRepositoryProvider;
  }

  @Override
  public ControlViewModel get() {
    return newInstance(bleManagerProvider.get(), safetySettingsRepositoryProvider.get(), userHabitsRepositoryProvider.get());
  }

  public static ControlViewModel_Factory create(Provider<BleManager> bleManagerProvider,
      Provider<ControlSafetySettingsRepository> safetySettingsRepositoryProvider,
      Provider<UserHabitsRepository> userHabitsRepositoryProvider) {
    return new ControlViewModel_Factory(bleManagerProvider, safetySettingsRepositoryProvider, userHabitsRepositoryProvider);
  }

  public static ControlViewModel newInstance(BleManager bleManager,
      ControlSafetySettingsRepository safetySettingsRepository,
      UserHabitsRepository userHabitsRepository) {
    return new ControlViewModel(bleManager, safetySettingsRepository, userHabitsRepository);
  }
}
