package com.ossm.remote.data.repository;

import com.ossm.remote.data.db.PresetDao;
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
public final class PresetRepository_Factory implements Factory<PresetRepository> {
  private final Provider<PresetDao> daoProvider;

  public PresetRepository_Factory(Provider<PresetDao> daoProvider) {
    this.daoProvider = daoProvider;
  }

  @Override
  public PresetRepository get() {
    return newInstance(daoProvider.get());
  }

  public static PresetRepository_Factory create(Provider<PresetDao> daoProvider) {
    return new PresetRepository_Factory(daoProvider);
  }

  public static PresetRepository newInstance(PresetDao dao) {
    return new PresetRepository(dao);
  }
}
