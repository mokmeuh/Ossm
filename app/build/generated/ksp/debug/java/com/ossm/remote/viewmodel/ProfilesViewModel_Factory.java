package com.ossm.remote.viewmodel;

import com.ossm.remote.data.repository.PresetRepository;
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
public final class ProfilesViewModel_Factory implements Factory<ProfilesViewModel> {
  private final Provider<PresetRepository> repositoryProvider;

  public ProfilesViewModel_Factory(Provider<PresetRepository> repositoryProvider) {
    this.repositoryProvider = repositoryProvider;
  }

  @Override
  public ProfilesViewModel get() {
    return newInstance(repositoryProvider.get());
  }

  public static ProfilesViewModel_Factory create(Provider<PresetRepository> repositoryProvider) {
    return new ProfilesViewModel_Factory(repositoryProvider);
  }

  public static ProfilesViewModel newInstance(PresetRepository repository) {
    return new ProfilesViewModel(repository);
  }
}
