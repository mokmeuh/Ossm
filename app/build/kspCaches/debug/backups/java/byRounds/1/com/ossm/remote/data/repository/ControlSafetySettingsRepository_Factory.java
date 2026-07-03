package com.ossm.remote.data.repository;

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
public final class ControlSafetySettingsRepository_Factory implements Factory<ControlSafetySettingsRepository> {
  private final Provider<Context> contextProvider;

  public ControlSafetySettingsRepository_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public ControlSafetySettingsRepository get() {
    return newInstance(contextProvider.get());
  }

  public static ControlSafetySettingsRepository_Factory create(Provider<Context> contextProvider) {
    return new ControlSafetySettingsRepository_Factory(contextProvider);
  }

  public static ControlSafetySettingsRepository newInstance(Context context) {
    return new ControlSafetySettingsRepository(context);
  }
}
