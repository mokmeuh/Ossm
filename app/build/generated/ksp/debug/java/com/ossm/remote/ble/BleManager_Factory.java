package com.ossm.remote.ble;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class BleManager_Factory implements Factory<BleManager> {
  private final Provider<Context> contextProvider;

  public BleManager_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public BleManager get() {
    return newInstance(contextProvider.get());
  }

  public static BleManager_Factory create(Provider<Context> contextProvider) {
    return new BleManager_Factory(contextProvider);
  }

  public static BleManager newInstance(Context context) {
    return new BleManager(context);
  }
}
