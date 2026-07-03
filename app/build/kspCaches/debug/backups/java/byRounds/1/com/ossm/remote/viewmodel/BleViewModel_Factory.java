package com.ossm.remote.viewmodel;

import com.ossm.remote.ble.BleManager;
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
public final class BleViewModel_Factory implements Factory<BleViewModel> {
  private final Provider<BleManager> bleManagerProvider;

  public BleViewModel_Factory(Provider<BleManager> bleManagerProvider) {
    this.bleManagerProvider = bleManagerProvider;
  }

  @Override
  public BleViewModel get() {
    return newInstance(bleManagerProvider.get());
  }

  public static BleViewModel_Factory create(Provider<BleManager> bleManagerProvider) {
    return new BleViewModel_Factory(bleManagerProvider);
  }

  public static BleViewModel newInstance(BleManager bleManager) {
    return new BleViewModel(bleManager);
  }
}
