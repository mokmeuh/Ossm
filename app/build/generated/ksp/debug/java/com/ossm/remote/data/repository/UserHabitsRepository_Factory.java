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
public final class UserHabitsRepository_Factory implements Factory<UserHabitsRepository> {
  private final Provider<Context> contextProvider;

  public UserHabitsRepository_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public UserHabitsRepository get() {
    return newInstance(contextProvider.get());
  }

  public static UserHabitsRepository_Factory create(Provider<Context> contextProvider) {
    return new UserHabitsRepository_Factory(contextProvider);
  }

  public static UserHabitsRepository newInstance(Context context) {
    return new UserHabitsRepository(context);
  }
}
