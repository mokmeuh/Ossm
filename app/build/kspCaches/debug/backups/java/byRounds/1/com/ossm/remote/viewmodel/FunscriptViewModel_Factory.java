package com.ossm.remote.viewmodel;

import android.content.Context;
import com.ossm.remote.ble.BleManager;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
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
public final class FunscriptViewModel_Factory implements Factory<FunscriptViewModel> {
  private final Provider<Context> contextProvider;

  private final Provider<BleManager> bleManagerProvider;

  public FunscriptViewModel_Factory(Provider<Context> contextProvider,
      Provider<BleManager> bleManagerProvider) {
    this.contextProvider = contextProvider;
    this.bleManagerProvider = bleManagerProvider;
  }

  @Override
  public FunscriptViewModel get() {
    return newInstance(contextProvider.get(), bleManagerProvider.get());
  }

  public static FunscriptViewModel_Factory create(Provider<Context> contextProvider,
      Provider<BleManager> bleManagerProvider) {
    return new FunscriptViewModel_Factory(contextProvider, bleManagerProvider);
  }

  public static FunscriptViewModel newInstance(Context context, BleManager bleManager) {
    return new FunscriptViewModel(context, bleManager);
  }
}
