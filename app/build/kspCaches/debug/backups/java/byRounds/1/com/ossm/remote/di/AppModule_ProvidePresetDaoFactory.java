package com.ossm.remote.di;

import com.ossm.remote.data.db.AppDatabase;
import com.ossm.remote.data.db.PresetDao;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
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
public final class AppModule_ProvidePresetDaoFactory implements Factory<PresetDao> {
  private final Provider<AppDatabase> dbProvider;

  public AppModule_ProvidePresetDaoFactory(Provider<AppDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public PresetDao get() {
    return providePresetDao(dbProvider.get());
  }

  public static AppModule_ProvidePresetDaoFactory create(Provider<AppDatabase> dbProvider) {
    return new AppModule_ProvidePresetDaoFactory(dbProvider);
  }

  public static PresetDao providePresetDao(AppDatabase db) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.providePresetDao(db));
  }
}
