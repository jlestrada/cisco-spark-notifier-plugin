package com.jenkins.plugins.sparknotify;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.core.Response.Status;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;
import net.sf.json.JSONObject;

public class SparkNotifyPostBuilder extends Recorder {
	private static final String JOB_FAILURE = "FAILURE";
	private static final String JOB_SUCCESS = "SUCCESS";
	private static final String JOB_ABORTED = "ABORTED";
	private static final String JOB_UNSTABLE = "UNSTABLE";

	private List<SparkRoom> roomList;
	private final boolean disable;
	private final boolean skipOnFailure;
	private final boolean skipOnSuccess;
	private final boolean skipOnAborted;
	private final boolean skipOnUnstable;
	private String message;
	private String messageType;
	private String messageContent;
	private String credentialsId;

	/**
	 * @deprecated Backwards compatibility; please use SparkSpace
	 */
	@Deprecated
	public static final class SparkRoom extends AbstractDescribableImpl<SparkRoom> {
		private final String rName;
		private final String rId;

		public String getRName() {
			return rName;
		}

		public String getRId() {
			return rId;
		}

		@DataBoundConstructor
		public SparkRoom(final String rName, final String rId) {
			this.rName = rName;
			this.rId = rId;
		}

		@Extension
		public static class DescriptorImpl extends Descriptor<SparkRoom> {
			@Override
			public String getDisplayName() {
				return "";
			}
		}
	}

	@DataBoundConstructor
	public SparkNotifyPostBuilder(final boolean disable, final boolean skipOnFailure, final boolean skipOnSuccess,
			final boolean skipOnAborted, final boolean skipOnUnstable, final String messageContent,
			final String messageType, final List<SparkRoom> roomList, final String credentialsId) {
		this.disable = disable;
		this.skipOnFailure = skipOnFailure;
		this.skipOnSuccess = skipOnSuccess;
		this.skipOnAborted = skipOnAborted;
		this.skipOnUnstable = skipOnUnstable;
		this.messageContent = messageContent;
		this.messageType = messageType;
		this.roomList = roomList;
		this.credentialsId = credentialsId;
	}

	public String getMessageContent() {
		return messageContent;
	}

	@DataBoundSetter
	public void setMessageContent(final String messageContent) {
		this.messageContent = messageContent;
	}

	public String getMessageType() {
		return messageType;
	}

	public boolean isDisable() {
		return disable;
	}

	public boolean isSkipOnFailure() {
		return skipOnFailure;
	}

	public boolean isSkipOnSuccess() {
		return skipOnSuccess;
	}

	public boolean isSkipOnAborted() {
		return skipOnAborted;
	}

	public boolean isSkipOnUnstable() {
		return skipOnUnstable;
	}

	public List<SparkRoom> getRoomList() {
		if (roomList == null) {
			roomList = new ArrayList<>();
		}
		return roomList;
	}

	public String getCredentialsId() {
		return credentialsId;
	}

	@DataBoundSetter
	public void setCredentialsId(final String credentialsId) {
		this.credentialsId = Util.fixEmpty(credentialsId);
	}

	/**
	 * @see hudson.tasks.BuildStepCompatibilityLayer#perform(hudson.model.AbstractBuild,
	 *      hudson.Launcher, hudson.model.BuildListener)
	 *
	 */
	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)
			throws InterruptedException, IOException {
		if (disable) {
			listener.getLogger().println("Spark Notify Plugin Disabled!");
			return true;
		}

		EnvVars envVars = build.getEnvironment(listener);

		message = getMessageContent();
		if (!SparkMessage.isMessageValid(message)) {
			listener.getLogger().println("Skipping spark notifications because no message was defined");
			return true;
		}

		String result = build.getResult().toString();
		if (result != null && !result.toString().isEmpty()) {
			message = message.replace("${BUILD_RESULT}", result);
		} else {
			listener.getLogger().println("Could not get result");
			result = "";
		}

		if (skipOnSuccess && result.equals(JOB_SUCCESS)) {
			listener.getLogger().println("Skipping spark notifications because job was successful");
			return true;
		}
		if (skipOnFailure && result.equals(JOB_FAILURE)) {
			listener.getLogger().println("Skipping spark notifications because job failed");
			return true;
		}
		if (skipOnAborted && result.equals(JOB_ABORTED)) {
			listener.getLogger().println("Skipping spark notifications because job was aborted");
			return true;
		}
		if (skipOnUnstable && result.equals(JOB_UNSTABLE)) {
			listener.getLogger().println("Skipping spark notifications because job is unstable");
			return true;
		}

		if (StringUtils.isEmpty(messageType)) {
			messageType = "text";
		}

		if (CollectionUtils.isEmpty(roomList)) {
			listener.getLogger().println("Skipping spark notifications because no rooms were defined");
			return true;
		}

		SparkMessageType sparkMessageType = SparkMessageType.valueOf(messageType.toUpperCase());

		SparkNotifier notifier = new SparkNotifier(getCredentials(credentialsId, build), envVars);

		for (int k = 0; k < roomList.size(); k++) {
			listener.getLogger().println("Sending message to Spark Room: " + roomList.get(k).getRId());
			try {
				int responseCode = notifier.sendMessage(roomList.get(k).getRId(), message, sparkMessageType);
				if (responseCode != Status.OK.getStatusCode()) {
					listener.getLogger().println("Could not send message; response code: " + responseCode);
				} else {
					listener.getLogger().println("Message sent");
				}
			} catch (SocketException e) {
				listener.getLogger().println(
						"Could not send message because ppark server did not provide a response; this is likely intermittent");
			} catch (SparkNotifyException e) {
				listener.getLogger().println(e.getMessage());
			} catch (RuntimeException e) {
				listener.getLogger().println(
						"Could not send message because of an unknown issue; please an issue");
			}
		}

		return true;
	}

	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@Override
	public boolean needsToRunAfterFinalized() {
		return true;
	}

	@Override
	public SparkNotifyPostBuilderDescriptor getDescriptor() {
		return (SparkNotifyPostBuilderDescriptor) super.getDescriptor();
	}

	@Extension
	public static final class SparkNotifyPostBuilderDescriptor extends BuildStepDescriptor<Publisher> {
		public SparkNotifyPostBuilderDescriptor() {
			super(SparkNotifyPostBuilder.class);
			load();
		}

		/**
		 * @see hudson.model.Descriptor#configure(org.kohsuke.stapler.StaplerRequest,
		 *      net.sf.json.JSONObject)
		 */
		@Override
		public boolean configure(final StaplerRequest req, final JSONObject formData) throws FormException {
			save();
			return true;
		}

		public FormValidation doMessageCheck(@QueryParameter final String message) {
			if (SparkMessage.isMessageValid(message)) {
				return FormValidation.ok();
			} else {
				return FormValidation.error("Message cannot be null");
			}
		}

		public FormValidation doRoomIdCheck(@QueryParameter final String roomId) {
			if (SparkMessage.isRoomIdValid(roomId)) {
				return FormValidation.ok();
			} else {
				return FormValidation.error("Invalid spaceId; see help message");
			}
		}

		/**
		 * @see hudson.tasks.BuildStepDescriptor#isApplicable(java.lang.Class)
		 */
		@SuppressWarnings("rawtypes")
		@Override
		public boolean isApplicable(final Class<? extends AbstractProject> jobType) {
			return true;
		}

		public ListBoxModel doFillCredentialsIdItems(@AncestorInPath final Job<?, ?> project,
				@QueryParameter final String serverURI) {
			return new StandardListBoxModel().withEmptySelection().withMatching(
					CredentialsMatchers.instanceOf(StringCredentials.class),
					CredentialsProvider.lookupCredentials(StringCredentials.class, project, ACL.SYSTEM,
							URIRequirementBuilder.fromUri(serverURI).build()));
		}

		public ListBoxModel doFillMessageTypeItems(@QueryParameter final String messageType) {
			return new ListBoxModel(new Option("text", "text", messageType.matches("text")),
					new Option("markdown", "markdown", messageType.matches("markdown")),
					new Option("html", "html", messageType.matches("html")));
		}

		/**
		 * @see hudson.model.Descriptor#getDisplayName()
		 */
		@Override
		public String getDisplayName() {
			return "Notify Spark Rooms";
		}
	}

	private Credentials getCredentials(final String credentialsId, final Run<?, ?> build) {
		return CredentialsProvider.findCredentialById(credentialsId, StringCredentials.class, build);
	}
}
