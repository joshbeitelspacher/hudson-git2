package hudson.plugins.git;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.model.ParametersAction;
import hudson.model.TaskListener;
import hudson.plugins.git.browser.GitWeb;
import hudson.scm.ChangeLogParser;
import hudson.scm.RepositoryBrowsers;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.util.FormFieldValidator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;

import javax.servlet.ServletException;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

public class GitSCM extends SCM implements Serializable {

	private static final long serialVersionUID = 1L;
	/** Source repository URL from which we pull. */
	private final String source;
	private final String branch;
	private final boolean clean;
	private final boolean doMerge;
	private final String mergeTarget;
	private GitWeb browser;

	@DataBoundConstructor
	public GitSCM(String source, String branch, boolean clean, boolean doMerge, String mergeTarget, GitWeb browser) {
		this.source = source;
		this.branch = branch;
		this.clean = clean;
		this.browser = browser;
		this.doMerge = doMerge;
		this.mergeTarget = mergeTarget;
	}

	@Override
	public boolean pollChanges(AbstractProject project, Launcher launcher, FilePath workspace, TaskListener listener)
			throws IOException, InterruptedException {
		listener.getLogger().println("Poll for changes");
		if (project.isBuilding()) {
			return false;
		}

		GitAPI git = new GitAPI(this.getDescriptor(), launcher, workspace, listener);
		this.ensureClonedAndFetched(git, listener);

		String tipHash = git.revParse(this.getBranch());
		String lastHash = this.getOrCreateLastProperty(project).getLastHashBuilt();
		listener.getLogger().println("tip = " + tipHash + ", last = " + lastHash);

		return (tipHash == null) ? false : !tipHash.equals(lastHash);
	}

	@Override
	public boolean checkout(AbstractBuild build, Launcher launcher, FilePath workspace, BuildListener listener,
			File changelogFile) throws IOException, InterruptedException {
		listener.getLogger().println("Checkout");
		workspace.mkdirs();

		GitAPI git = new GitAPI(this.getDescriptor(), launcher, workspace, listener);
		this.ensureClonedAndFetched(git, listener);

		// Only merge if there's a branch to merge that isn't us..
		if (this.doMerge && !this.getExpandedBranch(build).equals(this.getExpandedMergeTarget(build))) {
			listener.getLogger().println("Merging onto " + this.getMergeTarget());
			git.checkout(this.getExpandedMergeTarget(build));
			if (git.hasGitModules()) {
				git.submoduleUpdate();
			}
			try {
				git.merge(this.getExpandedBranch(build));
			} catch (Exception ex) {
				listener.getLogger().println("Branch not suitable for integration as it does not merge cleanly");
				return false;
			}
		} else {
			listener.getLogger().println("Checking out " + this.getExpandedBranch(build));
			git.checkout(this.getExpandedBranch(build));
			if (git.hasGitModules()) {
				git.submoduleUpdate();
			}
		}

		if (this.clean) {
			git.clean();
		}

		GitLastHashProperty lastProperty = this.getOrCreateLastProperty(build.getProject());
		String lastHash = lastProperty.getLastHashBuilt();
		String tipHash = git.revParse(this.getExpandedBranch(build));
		if ((lastHash != null) && (tipHash != null)) {
			git.log(lastHash, tipHash, changelogFile);
		}

		return true;
	}

	@Override
	public ChangeLogParser createChangeLogParser() {
		return new GitChangeLogParser();
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return DescriptorImpl.DESCRIPTOR;
	}

	public static final class DescriptorImpl extends SCMDescriptor<GitSCM> {
		public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
		private String gitExe;

		private DescriptorImpl() {
			super(GitSCM.class, GitWeb.class);
		}

		public String getDisplayName() {
			return "Git";
		}

		/** Path to git executable. */
		public String getGitExe() {
			if (this.gitExe == null) {
				return "git";
			}
			return this.gitExe;
		}

		public SCM newInstance(StaplerRequest req) throws FormException {
			return new GitSCM(req.getParameter("git.source"), req.getParameter("git.branch"), req
					.getParameter("git.clean") != null, req.getParameter("git.merge") != null, req
					.getParameter("git.mergeTarget"), RepositoryBrowsers
					.createInstance(GitWeb.class, req, "git.browser"));
		}

		public boolean configure(StaplerRequest req) throws FormException {
			this.gitExe = req.getParameter("git.gitExe");
			this.save();
			return true;
		}

		public void doGitExeCheck(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
			new FormFieldValidator.Executable(req, rsp) {
				protected void checkExecutable(File exe) throws IOException, ServletException {
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					try {
						Proc proc = Hudson.getInstance().createLauncher(TaskListener.NULL).launch(
								new String[] { DescriptorImpl.this.getGitExe(), "--version" }, new String[0], baos,
								null);
						proc.join();
						String result = baos.toString();
						this.ok();
					} catch (Exception e) {
						this.error("Unable to check git version");
					}
				}
			}.process();
		}
	}

	/** Gets the source repository path. Either URL or local file path. */
	public String getSource() {
		return this.source;
	}

	public String getBranch() {
		return this.branch;
	}

	public boolean getClean() {
		return this.clean;
	}

	@Override
	public GitWeb getBrowser() {
		return this.browser;
	}

	public boolean getDoMerge() {
		return this.doMerge;
	}

	public String getMergeTarget() {
		return this.mergeTarget;
	}

	public String getExpandedBranch(AbstractBuild build) {
		ParametersAction parameters = build.getAction(ParametersAction.class);
		if (parameters != null) {
			return parameters.substitute(build, this.branch);
		}
		return this.branch;
	}

	public String getExpandedMergeTarget(AbstractBuild build) {
		ParametersAction parameters = build.getAction(ParametersAction.class);
		if (parameters != null) {
			return parameters.substitute(build, this.mergeTarget);
		}
		return this.mergeTarget;
	}

	private void ensureClonedAndFetched(GitAPI git, TaskListener listener) throws IOException, InterruptedException {
		if (!git.hasGitRepo()) {
			listener.getLogger().println("Cloning " + this.source);
			git.clone(this.source);
			if (git.hasGitModules()) {
				git.submoduleInit();
			}
		}
		git.fetch();
	}

	public GitLastHashProperty getOrCreateLastProperty(AbstractProject project) throws IOException {
		GitLastHashProperty p = (GitLastHashProperty) project.getProperty(GitLastHashProperty.class);
		if (p == null) {
			p = new GitLastHashProperty(null);
			project.addProperty(p);
		}
		return p;
	}

	public void buildEnvVars(AbstractBuild build, Map<String, String> env) {
		super.buildEnvVars(build, env);
		GitAPI git = new GitAPI(this.getDescriptor(), Hudson.getInstance().createLauncher(TaskListener.NULL), build
				.getProject().getWorkspace(), TaskListener.NULL);
		try {
			String rev = git.revParse(this.getRemoteBranch());
			env.put("GIT_REVISION", rev);
			rev = git.revParse(this.getRemoteBranch(), true);
			env.put("GIT_REVISION_SHORT", rev);
		} catch (Exception e) {

		}
	}
}
