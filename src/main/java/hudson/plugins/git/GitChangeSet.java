package hudson.plugins.git;

import hudson.model.User;
import hudson.scm.ChangeLogSet;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/**
 * Represents a change set.
 *
 * @author Nigel Magnay
 */
public class GitChangeSet extends ChangeLogSet.Entry {

	private Collection<String> affectedPaths = new HashSet<String>();
	private String author;
	private String msg;
	private String id;

	public GitChangeSet(List<String> lines) {
		if (lines.size() > 0) {
			this.parseCommit(lines);
		}
	}

	private void parseCommit(List<String> lines) {
		String comment = "";

		for (String line : lines) {
			if (line.length() > 0) {
				if (line.startsWith("commit ")) {
					this.id = line.split(" ")[1];
				} else if (line.startsWith("tree ")) {
				} else if (line.startsWith("parent ")) {
					// parent
				} else if (line.startsWith("committer ")) {
					this.author = line.substring(10, line.indexOf(" <"));

				} else if (line.startsWith("author ")) {
				} else if (line.startsWith("    ")) {
					comment += line.substring(4) + "\n";
				} else if (line.startsWith("A\t") || line.startsWith("C\t") || line.startsWith("D\t")
						|| line.startsWith("M\t") || line.startsWith("R\t") || line.startsWith("T\t")) {
					this.affectedPaths.add(line.substring(2));
				} else {
					// Ignore
				}
			}
		}

		this.msg = comment;
	}

	public void setParent(ChangeLogSet parent) {
		super.setParent(parent);
	}

	@Override
	public Collection<String> getAffectedPaths() {
		return this.affectedPaths;
	}

	@Override
	public User getAuthor() {
		return User.get(this.author, true);
	}

	@Override
	public String getMsg() {
		return this.msg;
	}

	public String getId() {
		return this.id;
	}

}
