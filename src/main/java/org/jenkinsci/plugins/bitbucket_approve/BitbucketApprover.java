package org.jenkinsci.plugins.bitbucket_approve;

import com.squareup.okhttp.Credentials;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.MediaType;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.BuildData;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused") // This class will be loaded using its Descriptor.
public class BitbucketApprover extends Notifier {

    private final OkHttpClient mClient;

    private String mOwner;

    private String mSlug;

    private boolean mApproveUnstable;

    private String mApprovalMethod;

    @DataBoundConstructor
    public BitbucketApprover(String owner, String slug, boolean approveUnstable, String approvalMethod) {
        mOwner = owner;
        mSlug = slug;
        mApproveUnstable = approveUnstable;
        mApprovalMethod = approvalMethod;

        mClient = new OkHttpClient();
        mClient.setConnectTimeout(30, TimeUnit.SECONDS);
        mClient.setReadTimeout(60, TimeUnit.SECONDS);
    }

    public String getSlug() {
        return mSlug;
    }

    public String getOwner() {
        return mOwner;
    }

    public boolean getApproveUnstable() {
        return mApproveUnstable;
    }

    public String getApprovalMethod() {
        return mApprovalMethod;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        PrintStream logger = listener.getLogger();

        BuildData buildData = build.getAction(BuildData.class);
        if (buildData == null) {
            logger.println("Bitbucket Approve: Could not get build data from build.");
            return false;
        }

        Revision rev = buildData.getLastBuiltRevision();
        if (rev == null) {
            logger.println("Bitbucket Approve: Could not get revision from build.");
            return false;
        }

        String commitHash = rev.getSha1String();
        if (commitHash == null) {
            logger.println("Bitbucket Approve: Could not get commit hash from build data.");
            return false;
        }

        // Previous versions did not know about approval method. This makes sure the upgrade
        // does not break builds and we keep the same behaviour as before.
        if (mApprovalMethod == null) {
            mApprovalMethod = "approveOnly";
        }

        if (!mApprovalMethod.equals("statusOnly")) {
            approveBuild(build, listener, commitHash, logger);
        } else {
            logger.println("Bitbucket Approve: Skipping approval because we only set the status.");
        }

        if (!mApprovalMethod.equals("approveOnly")) {
            postBuildStatus(build, listener, commitHash, logger);
        } else {
            logger.println("Bitbucket Approve: Skipping build status because we only approve commits.");
        }

        return true;
    }

    private void approveBuild(AbstractBuild build, BuildListener listener, String commitHash, PrintStream logger) throws IOException, InterruptedException {
        if (build.getResult().isWorseThan(Result.UNSTABLE) ||
                build.getResult().ordinal == Result.UNSTABLE.ordinal && !mApproveUnstable) {
            logger.println("Bitbucket Approve: Skipping approval because build is " + build.getResult().toString());
            return;
        }

        String eOwner = build.getEnvironment(listener).expand(mOwner);
        String eSlug  = build.getEnvironment(listener).expand(mSlug);
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/commit/%s/approve", eOwner, eSlug, commitHash);
        logger.println("Bitbucket Approve: " + url);

        Request.Builder builder = new Request.Builder();
        Request request = builder.header("Authorization", getDescriptor().getBasicAuth())
                .url(url)
                .method("POST", null).build();

        try {
            Response response = mClient.newCall(request).execute();

            if (!isSuccessful(response)) {
                logger.println("Bitbucket Approve: " + response.code() + " - " + response.message());
                logger.println("Bitbucket Approve: " + response.body().string());
            }
        } catch (IOException e) {
            e.printStackTrace(listener.getLogger());
        }
    }

    private void postBuildStatus(AbstractBuild build, BuildListener listener, String commitHash, PrintStream logger) throws IOException, InterruptedException {
        String eOwner = build.getEnvironment(listener).expand(mOwner);
        String eSlug  = build.getEnvironment(listener).expand(mSlug);
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/commit/%s/statuses/build", eOwner, eSlug, commitHash);
        logger.println("Bitbucket Approve: " + url);

        String state = (build.getResult().ordinal == Result.SUCCESS.ordinal) ? "SUCCESSFUL" : "FAILED";
        String key = build.getProject().getDisplayName();
        String name = "Build #" + build.getId();
        String rootUrl = Jenkins.getInstance().getRootUrl();
        String buildUrl = (rootUrl == null) ? "PLEASE SET JENKINS ROOT URL IN GLOBAL CONFIG " + build.getUrl() : rootUrl + build.getUrl();
        String description = build.getProject().getDisplayName() + ' ' + commitHash.substring(0, 7);

        String json = "{\"state\": \"" + state
         + "\",\"key\": \"" + key
         + "\",\"name\": \"" + name
         + "\",\"url\": \"" + buildUrl
         + "\",\"description\": \"" + description + "\"}";

        MediaType mediaJson = MediaType.parse("application/json; charset=utf-8");
        RequestBody statusBody = RequestBody.create(mediaJson, json);

        Request.Builder builder = new Request.Builder();
        Request statusRequest = builder.header("Authorization", getDescriptor().getBasicAuth())
                .url(url)
                .method("POST", statusBody).build();

        try {
            Response statusResponse = mClient.newCall(statusRequest).execute();

            if (!isSuccessful(statusResponse)) {
                logger.println("Bitbucket Approve (sending status): " + statusResponse.code() + " - " + statusResponse.message());
                logger.println("Bitbucket Approve (sending status): " + statusResponse.body().string());
            }
        } catch (IOException e) {
            e.printStackTrace(logger);
        }
    }

    /**
     * A 409 CONFLICT response means that we already approved this changeset.
     * We do not consider that an error.
     */
    private boolean isSuccessful(Response response) throws IOException {
        return response.isSuccessful() ||
                response.code() == HttpURLConnection.HTTP_CONFLICT;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String mUser;

        private String mPassword;

        /**
         * In order to load the persisted global configuration, you have to
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Approve commit on Bitbucket";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            mUser = formData.getString("user");
            mPassword = formData.getString("password");

            save();

            return super.configure(req, formData);
        }

        public String getUser() {
            return mUser;
        }

        public String getPassword() {
            return mPassword;
        }

        public String getBasicAuth() {
            return Credentials.basic(mUser, mPassword);
        }
    }
}

