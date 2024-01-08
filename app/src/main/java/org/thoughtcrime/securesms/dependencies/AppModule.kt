package org.thoughtcrime.securesms.dependencies

import android.content.Context
import android.widget.Toast
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.session.libsession.utilities.AppTextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.Toaster
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.repository.DefaultConversationRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindTextSecurePreferences(preferences: AppTextSecurePreferences): TextSecurePreferences

    @Binds
    abstract fun bindConversationRepository(repository: DefaultConversationRepository): ConversationRepository

}

@Module
@InstallIn(SingletonComponent::class)
class ToasterModule {
    @Provides
    @Singleton
    fun provideToaster(@ApplicationContext context: Context) = Toaster { stringRes, toastLength, parameters ->
        val string = context.getString(stringRes, parameters)
        Toast.makeText(context, string, toastLength).show()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppComponent {
    fun getPrefs(): TextSecurePreferences
}