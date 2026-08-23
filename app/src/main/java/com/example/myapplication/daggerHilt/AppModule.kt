package com.example.myapplication.daggerHilt

import android.content.Context
import com.coder.vincent.sharp_retrofit.call_adapter.flow.FlowCallAdapterFactory
import com.example.myapplication.database.chatHistory.ChatHistoryDatabase
import com.example.myapplication.database.im.IMDatabase
import com.example.myapplication.database.im.dao.ConversationDao
import com.example.myapplication.database.im.dao.MessageDao
import com.example.myapplication.network.auth.AuthService
import com.example.myapplication.network.chatList.ChatListService
import com.example.myapplication.network.eachChatRecord.ChatRecordService
import com.example.myapplication.network.uploadFile.UploadFileService
import com.example.myapplication.network.user.UserService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideClient(@ApplicationContext context: Context): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            Log.d("IM_HTTP", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val request = chain.request()
                Log.d("IM_HTTP", ">>> 请求: ${request.method} ${request.url}")
                try {
                    val response = chain.proceed(request)
                    Log.d("IM_HTTP", "<<< 响应: ${response.code} ${response.message} for ${request.url}")
                    response
                } catch (e: Exception) {
                    Log.e("IM_HTTP", "!!! 请求异常: ${e.javaClass.simpleName}: ${e.message}", e)
                    throw e
                }
            }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * 在目录中使用Hilt提供多个相对类型的实例对象，通过"@Named"注解进行区分
     * 也可以通过"@Qualifier"限定符定义新的注解进行区分*/

    @Singleton
    @Provides
    @Named("upload")
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://blob.datapipe.top")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .addCallAdapterFactory(FlowCallAdapterFactory.create())
            .build()
    }

    @Singleton
    @Provides
    @Named("chat")
    fun provideRetrofitChat(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://c.datapipe.top")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .addCallAdapterFactory(FlowCallAdapterFactory.create())
            .build()
    }

    @Singleton
    @Provides
    @Named("auth")
    fun provideRetrofitAuth(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("http://192.168.148.15:8081/")   //换成电脑的ip4地址，确保手机和电脑连接同一个网络
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Singleton
    @Provides
    fun provideUploadFileService(@Named("upload") retrofit: Retrofit): UploadFileService {
        return retrofit.create(UploadFileService::class.java)
    }

    @Singleton
    @Provides
    fun provideChatListService(@Named("chat") retrofit: Retrofit):ChatListService{
        return retrofit.create(ChatListService::class.java)
    }

    @Singleton
    @Provides
    fun provideChatRecordService(@Named("chat") retrofit: Retrofit):ChatRecordService{
        return retrofit.create(ChatRecordService::class.java)
    }

    @Singleton
    @Provides
    fun provideAuthService(@Named("auth") retrofit: Retrofit): AuthService {
        return retrofit.create(AuthService::class.java)
    }

    @Singleton
    @Provides
    fun provideUserService(@Named("auth") retrofit: Retrofit): UserService {
        return retrofit.create(UserService::class.java)
    }

    @Singleton
    @Provides
    fun provideChatHistoryDatabase(@ApplicationContext context: Context): ChatHistoryDatabase {
        return ChatHistoryDatabase.getDatabase(context)
    }

    @Singleton
    @Provides
    fun provideChatHistoryDao(database: ChatHistoryDatabase) = database.chatHistoryDao()

    @Singleton
    @Provides
    fun provideIMDatabase(@ApplicationContext context: Context): IMDatabase {
        return IMDatabase.getDatabase(context)
    }

    @Singleton
    @Provides
    fun provideMessageDao(database: IMDatabase): MessageDao {
        return database.messageDao()
    }

    @Singleton
    @Provides
    fun provideConversationDao(database: IMDatabase): ConversationDao {
        return database.conversationDao()
    }
}