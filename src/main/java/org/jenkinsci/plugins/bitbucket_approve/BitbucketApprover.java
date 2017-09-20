package org.jenkinsci.plugins.bitbucket_approve;

import com.squareup.okhttp.Credentials;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.BuildData;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused") // This class will be loaded using its Descriptor.
public class BitbucketApprover extends Notifier {

    private String mOwner;

    private String mSlug;

    @DataBoundConstructor
    public BitbucketApprover(String owner, String slug) {
        mOwner = owner;
        mSlug = slug;
    }

    public String getSlug() {
        return mSlug;
    }

    public String getOwner() {
        return mOwner;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        PrintStream logger = listener.getLogger();

        if (build.getResult().isWorseOrEqualTo(Result.FAILURE)) {
            logger.println("Bitbucket Approve: Skipping because of FAILURE");
            return true;
        }

        BuildData buildData = build.getAction(BuildData.class);
        if (buildData == null) {
            logger.println("Bitbucket Approve: Could not get build data from build.");
            return false;
        }

        Revision rev = buildData.getLastBuiltRevision();
        if (buildData == null) {
            logger.println("Bitbucket Approve: Could not get revision from build.");
            return false;
        }

        String commitHash = rev.getSha1String();
        if (commitHash == null) {
            logger.println("Bitbucket Approve: Could not get commit hash from build data.");
            return false;
        }

        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/commit/%s/approve", mOwner, mSlug, commitHash);
        logger.println("Bitbucket Approve: " + url);

        OkHttpClient client = new OkHttpClient();
        client.setConnectTimeout(30, TimeUnit.SECONDS);
        client.setReadTimeout(60, TimeUnit.SECONDS);

        Request.Builder builder = new Request.Builder();
        Request request = builder.header("Authorization", getDescriptor().getBasicAuth())
                .url(url)
                .method("POST", null).build();

        try {
            Response response = client.newCall(request).execute();

            if (isSuccessful(response)) {
                return true;
            }

            logger.println("Bitbucket Approve: " + response.code() + " - " + response.message());
            logger.println("Bitbucket Approve: " + response.body().string());

        } catch (IOException e) {
            e.printStackTrace(listener.getLogger());
        }

        return false;
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

