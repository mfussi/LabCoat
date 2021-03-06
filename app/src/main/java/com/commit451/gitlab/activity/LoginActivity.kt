package com.commit451.gitlab.activity

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import androidx.appcompat.widget.Toolbar
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import butterknife.BindView
import butterknife.ButterKnife
import butterknife.OnClick
import com.afollestad.materialdialogs.MaterialDialog
import com.commit451.gitlab.App
import com.commit451.gitlab.BuildConfig
import com.commit451.gitlab.R
import com.commit451.gitlab.api.GitLab
import com.commit451.gitlab.api.GitLabFactory
import com.commit451.gitlab.api.MoshiProvider
import com.commit451.gitlab.api.OkHttpClientFactory
import com.commit451.gitlab.data.Prefs
import com.commit451.gitlab.dialog.HttpLoginDialog
import com.commit451.gitlab.event.LoginEvent
import com.commit451.gitlab.event.ReloadDataEvent
import com.commit451.gitlab.extension.checkValid
import com.commit451.gitlab.extension.text
import com.commit451.gitlab.extension.with
import com.commit451.gitlab.model.Account
import com.commit451.gitlab.model.api.Message
import com.commit451.gitlab.model.api.User
import com.commit451.gitlab.navigation.Navigator
import com.commit451.gitlab.rx.CustomResponseSingleObserver
import com.commit451.gitlab.ssl.CustomHostnameVerifier
import com.commit451.gitlab.ssl.X509CertificateException
import com.commit451.gitlab.ssl.X509Util
import com.commit451.gitlab.util.IntentUtil
import com.commit451.teleprinter.Teleprinter
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import java.util.*
import java.util.regex.Pattern
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException


class LoginActivity : BaseActivity() {

    companion object {

        private val tokenPattern: Pattern by lazy {
            Pattern.compile("^[A-Za-z0-9-_]*$")
        }

        private const val KEY_SHOW_CLOSE = "show_close"

        fun newIntent(context: Context, showClose: Boolean = false): Intent {
            val intent = Intent(context, LoginActivity::class.java)
            intent.putExtra(KEY_SHOW_CLOSE, showClose)
            return intent
        }
    }

    @BindView(R.id.root)
    lateinit var root: View
    @BindView(R.id.toolbar)
    lateinit var toolbar: Toolbar
    @BindView(R.id.text_input_layout_server)
    lateinit var textInputLayoutUrl: TextInputLayout
    @BindView(R.id.token_hint)
    lateinit var textInputLayoutToken: TextInputLayout
    @BindView(R.id.token_input)
    lateinit var textToken: TextView
    @BindView(R.id.progress)
    lateinit var progress: View

    lateinit var teleprinter: Teleprinter

    var currentAccount: Account = Account()
    var currentGitLab: GitLab? = null

    @OnClick(R.id.button_info)
    fun onInfoClicked() {
        MaterialDialog.Builder(this)
                .title(R.string.access_token_info_title)
                .content(R.string.access_token_info_message)
                .positiveText(R.string.create_personal_access_token)
                .onPositive { _, _ ->
                    val validUrl = verifyUrl()
                    if (validUrl) {
                        val url = textInputLayoutUrl.text()
                        val accessTokenUrl = "$url/profile/personal_access_tokens"
                        IntentUtil.openPage(this, accessTokenUrl)
                    } else {
                        Snackbar.make(root, R.string.not_a_valid_url, Snackbar.LENGTH_SHORT)
                                .show()
                    }
                }
                .negativeText(R.string.cancel)
                .show()
    }

    @OnClick(R.id.login_button)
    fun onLoginClick() {
        teleprinter.hideKeyboard()

        if (!textInputLayoutUrl.checkValid()) {
            return
        }

        if (!verifyUrl()) {
            return
        }
        val uri = textInputLayoutUrl.text()

        if (!textInputLayoutToken.checkValid()) {
            return
        }
        if (!tokenPattern.matcher(textToken.text).matches()) {
            textInputLayoutToken.error = getString(R.string.not_a_valid_private_token)
            return
        } else {
            textInputLayoutToken.error = null
        }


        if (isAlreadySignedIn(uri, textToken.text.toString())) {
            Snackbar.make(root, getString(R.string.already_logged_in), Snackbar.LENGTH_LONG)
                    .show()
            return
        }

        currentAccount = Account()
        currentAccount.serverUrl = uri

        login()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        ButterKnife.bind(this)

        teleprinter = Teleprinter(this)
        val showClose = intent.getBooleanExtra(KEY_SHOW_CLOSE, false)

        if (showClose) {
            toolbar.setNavigationIcon(R.drawable.ic_close_24dp)
            toolbar.setNavigationOnClickListener { onBackPressed() }
        }

        textInputLayoutUrl.editText?.setText(R.string.url_gitlab)
    }

    override fun hasBrowsableLinks(): Boolean {
        return true
    }

    fun verifyUrl(): Boolean {
        val url = textInputLayoutUrl.text()
        var uri: Uri? = null
        try {
            if (HttpUrl.parse(url) != null) {
                uri = Uri.parse(url)
            }
        } catch (e: Exception) {
            Timber.e(e)
        }

        if (uri == null) {
            textInputLayoutUrl.error = getString(R.string.not_a_valid_url)
            return false
        } else {
            textInputLayoutUrl.error = null
        }
        if (!url.endsWith("/")) {
            textInputLayoutUrl.editText?.setText(url + "/")
        } else {
            textInputLayoutUrl.error = null
        }
        return true
    }

    fun login() {
        // This seems useless - But believe me, it makes everything work! Don't remove it.
        // (OkHttpClientFactory caches the clients and needs a new account to recreate them)
        val newAccount = Account()
        newAccount.serverUrl = currentAccount.serverUrl
        newAccount.trustedCertificate = currentAccount.trustedCertificate
        newAccount.trustedHostname = currentAccount.trustedHostname
        newAccount.authorizationHeader = currentAccount.authorizationHeader
        currentAccount = newAccount

        progress.visibility = View.VISIBLE
        progress.alpha = 0.0f
        progress.animate().alpha(1.0f)

        currentAccount.privateToken = textToken.text.toString()
        val gitlabClientBuilder = OkHttpClientFactory.create(currentAccount, false)
        if (BuildConfig.DEBUG) {
            gitlabClientBuilder.addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
        }

        currentGitLab = GitLabFactory.createGitLab(currentAccount, gitlabClientBuilder)

        loadUser(gitlabClientBuilder)
    }

    fun loadUser(gitlabClientBuilder: OkHttpClient.Builder) {

        val gitLabService = GitLabFactory.create(currentAccount, gitlabClientBuilder.build())
        gitLabService.getThisUser()
                .with(this)
                .subscribe(object : CustomResponseSingleObserver<User>() {

                    override fun error(e: Throwable) {
                        Timber.e(e)
                        if (e is HttpException) {
                            handleConnectionResponse(response(), e)
                        } else {
                            handleConnectionError(e)
                        }
                    }

                    override fun responseNonNullSuccess(userFull: User) {
                        progress.visibility = View.GONE
                        currentAccount.lastUsed = Date()
                        currentAccount.email = userFull.email
                        currentAccount.username = userFull.username
                        Prefs.addAccount(currentAccount)
                        App.get().setAccount(currentAccount)
                        App.bus().post(LoginEvent(currentAccount))
                        //This is mostly for if projects already exists, then we will reload the data
                        App.bus().post(ReloadDataEvent())
                        Navigator.navigateToStartingActivity(this@LoginActivity)
                        finish()
                    }
                })
    }

    fun handleConnectionError(t: Throwable) {
        progress.visibility = View.GONE

        if (t is SSLHandshakeException && t.cause is X509CertificateException) {
            currentAccount.trustedCertificate = null
            val fingerprint = X509Util.getFingerPrint((t.cause as X509CertificateException).chain[0])

            val dialog = AlertDialog.Builder(this)
                    .setTitle(R.string.certificate_title)
                    .setMessage(String.format(resources.getString(R.string.certificate_message), fingerprint))
                    .setPositiveButton(R.string.ok_button) { dialog, _ ->
                        currentAccount.trustedCertificate = fingerprint
                        login()
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.cancel_button) { dialog, _ -> dialog.dismiss() }
                    .show()

            dialog.findViewById<TextView>(android.R.id.message).movementMethod = LinkMovementMethod.getInstance()
        } else if (t is SSLPeerUnverifiedException && t.message?.toLowerCase()!!.contains("hostname")) {
            currentAccount.trustedHostname = null
            val hostNameVerifier = currentGitLab?.client?.hostnameVerifier() as? CustomHostnameVerifier
            val finalHostname = hostNameVerifier?.lastFailedHostname
            val dialog = AlertDialog.Builder(this)
                    .setTitle(R.string.hostname_title)
                    .setMessage(R.string.hostname_message)
                    .setPositiveButton(R.string.ok_button) { dialog, _ ->
                        if (finalHostname != null) {
                            currentAccount.trustedHostname = finalHostname
                            login()
                        }

                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.cancel_button) { dialog, _ -> dialog.dismiss() }
                    .show()

            dialog.findViewById<TextView>(android.R.id.message).movementMethod = LinkMovementMethod.getInstance()
        } else {
            snackbarWithDetails(t)
        }
    }

    fun handleConnectionResponse(response: Response<*>, throwable: Throwable) {
        progress.visibility = View.GONE
        when (response.code()) {
            401 -> {
                currentAccount.authorizationHeader = null

                val header = response.headers().get("WWW-Authenticate")
                if (header != null) {
                    handleBasicAuthentication(response)
                    return
                }
                var errorMessage = getString(R.string.login_unauthorized)
                try {
                    val adapter = MoshiProvider.moshi.adapter<Message>(Message::class.java)
                    val message = adapter.fromJson(response.errorBody()!!.string())
                    if (message?.message != null) {
                        errorMessage = message.message
                    }
                } catch (e: IOException) {
                    Timber.e(e)
                }

                Snackbar.make(root, errorMessage, Snackbar.LENGTH_LONG)
                        .show()
                return
            }
            404 -> {
                Snackbar.make(root, getString(R.string.login_404_error), Snackbar.LENGTH_LONG)
                        .show()
            }
            else -> snackbarWithDetails(throwable)
        }
    }

    fun handleBasicAuthentication(response: Response<*>) {
        val header = response.headers().get("WWW-Authenticate")!!.trim { it <= ' ' }
        if (!header.startsWith("Basic")) {
            Snackbar.make(root, getString(R.string.login_unsupported_authentication), Snackbar.LENGTH_LONG)
                    .show()
            return
        }

        val realmStart = header.indexOf('"') + 1
        val realmEnd = header.lastIndexOf('"')
        var realm = ""
        if (realmStart > 0 && realmEnd > -1) {
            realm = header.substring(realmStart, realmEnd)
        }

        val dialog = HttpLoginDialog(this, realm, object : HttpLoginDialog.LoginListener {
            override fun onLogin(username: String, password: String) {
                currentAccount.authorizationHeader = Credentials.basic(username, password)
                login()
            }

            override fun onCancel() {}
        })
        dialog.show()
    }

    fun isAlreadySignedIn(url: String, usernameOrEmailOrPrivateToken: String): Boolean {
        val accounts = Prefs.getAccounts()
        return accounts.any {
            it.serverUrl == url && usernameOrEmailOrPrivateToken == it.privateToken
        }
    }

    fun snackbarWithDetails(throwable: Throwable) {
        Snackbar.make(root, getString(R.string.login_error), Snackbar.LENGTH_LONG)
                .setAction(R.string.details, {
                    val details = throwable.message ?: getString(R.string.no_error_details)
                    MaterialDialog.Builder(this)
                            .title(R.string.error)
                            .content(details)
                            .positiveText(R.string.ok)
                            .show()
                })
                .show()
    }
}
