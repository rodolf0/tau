package ponytrivia.importer;

import java.io.EOFException;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ponytrivia.db.Batch;
import ponytrivia.db.Schema;

public class Importer {
	public Schema schema;

	public Importer(Schema schema) {
		this.schema = schema;
	}

	public void import_all(String directory) throws IOException, SQLException {
		import_movies(directory);
	//	import_roles(directory);
	}

	protected void import_movies(String directory) throws IOException,
			SQLException {
		//final Pattern line_pat = Pattern
			//	.compile("(.+?)\\s+\\((\\d+)\\)\\s+(?:\\{(.*?)\\})??");
		final Pattern line_pat = Pattern
		.compile("(.+?)\\s+\\((\\d+)\\)\\s+(\\{(.*?)\\})??(.*)??");
		ListFileParser parser = new ListFileParser(directory + "/movies_short.txt");
		parser.skipUntil("^MOVIES\\s+LIST\\s*$");
		parser.skipUntil("^=+\\s*$");

		Batch batch = schema.createBatch();

		for (int i = 0; i < 10; i++) {
			String line = parser.readLine();
			if (line == null) {
				break;
			}
			if (line.trim().isEmpty()) {
				continue;
			}
			Matcher m = line_pat.matcher(line);
			if (!m.matches()) {
				continue;
			}
			String name = m.group(1);
			String year_s = m.group(2);
			String episode = m.group(3);
			boolean tvshow = name.startsWith("\"");
			int year = -1;
			String full_name = name + "/" + year + "/" + episode;

			try {
				year = Integer.parseInt(year_s);
			} catch (NumberFormatException ex) {
			}

			batch.add("INSERT IGNORE INTO Movies (imdb_name, type, name, year) "
					+ "VALUES ("
					+ full_name
					+ ", "
					+ (tvshow ? "true" : "false")
					+ ", "
					+ name
					+ ", "
					+ (year > 1900 ? year : "NULL") + ")");
		}
		batch.close();
	}

	//gender: 0 - f, 1 - m 
	protected void _import_actors(ListFileParser parser,int gender) throws IOException {
		Pattern title_pat = Pattern
				.compile("(.+)\\s+\\((\\d+)\\)\\s+(\\[(.+)\\])??\\s*(\\<(\\d+)\\>)??");

		Batch batch;
		try {
			batch = schema.createBatch();
		} catch (SQLException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		while (true) {
			List<String> lines = parser.readUntil("^\\s*$");
			if (lines == null) {
				break;
			}
			String imdb_name = null;
			int pid = 0;
			String first_name;
			String last_name;
			for (String line : lines) {
				String title;
				String character = null;
				int credits = 0;
				if (imdb_name == null) {
					String parts[] = line.split("\t+");
					imdb_name = parts[0];
					title = parts[1];
					String names[] = imdb_name.split(",");
					first_name = names[1];
					last_name = names[0];
					try {
						schema.executeUpdate("INSERT IGNORE INTO People (imdb_name,first_name,last_name,gender)"
								+ " VALUES ("
								+ imdb_name
								+ ", "
								+ first_name
								+ ", " + last_name + ")");

						pid = schema
								.getForeignKey("SELECT P.id FROM People as P where P.imdb_name = "
										+ imdb_name);
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						continue;
					}

				} else {
					String parts[] = line.split("\t+");
					title = parts[0];
				}
				Matcher m = title_pat.matcher(title);
				String movie_name = m.group(1);
				String year = m.group(2);
				try {

					character = m.group(4);
					credits = Integer.parseInt(m.group(6));
				} catch (IndexOutOfBoundsException e) {
					// if (character==null)
				}

				try {
					int mid = schema
							.getForeignKey("SELECT M.movie_id from movies as M where "
									+ "M.imdb_name = '" + movie_name + "'");

					batch.add("INSERT IGNORE INTO Roles (actor, movie,character,credit_pos) VALUES ("
							+ pid
							+ ", "
							+ mid
							+ ", "
							+ character
							+ ", "
							+ credits);
				} catch (SQLException e) {
					// TODO Auto-generated catch block
					continue;
				}
			}
		}
	}

	protected void import_roles(String directory) throws IOException {
		ListFileParser parser = new ListFileParser(directory + "/actor.list");
		parser.skipUntil("^Name\\s+Titles\\s*$");
		parser.skipUntil("^-*\\s+-*\\s*$");
		_import_actors(parser,1);

		parser = new ListFileParser(directory + "/actresses.list");
		parser.skipUntil("^Name\\s+Titles\\s*$");
		parser.skipUntil("^-*\\s+-*\\s*$");
		_import_actors(parser,0);
	}

	protected void import_genres(String directory) throws IOException,
			SQLException {
		ListFileParser parser = new ListFileParser(directory + "/genres.list");
		parser.skipUntil("^8:\\s+THE\\s+GENRES\\s+LIST\\s*$");
		parser.skipUntil("^=+\\s*$");
		parser.readLine();
		Batch batch = schema.createBatch();
		while (true) {

			String line = parser.readLine();
			if (line == null) {
				break;
			}
			int gid;

			String[] parts = line.split("\\t");
			String genre = parts[1];
			String imdb_name = parts[0];
			try {
				schema.executeUpdate("INSERT IGNORE INTO genre (genre)"
						+ " VALUES (" + genre + ")");

				gid = schema
						.getForeignKey("SELECT genre.id FROM genre as G where G.genre = "
								+ genre);

				int mid = schema
						.getForeignKey("SELECT M.movie_id from movies as M where "
								+ "M.imdb_name = '" + imdb_name + "'");

				batch.add("INSERT IGNORE INTO movie_genre (movie_id,genre_id) VALUES ("
						+ mid + ", " + gid + ")");
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				continue;
			}
		}

		batch.close();
	}

	protected void import_directors(String directory) throws IOException, SQLException {
		ListFileParser parser = new ListFileParser(directory
				+ "/directors.list");
		Pattern title_pat = Pattern
				.compile("(.+)\\s+\\((\\d+)\\)\\s+(\\[(.+)\\])??\\s*(\\<(\\d+)\\>)??");

		Batch batch = schema.createBatch();

		while (true) {
			List<String> lines = parser.readUntil("^\\s*$");
			if (lines == null) {
				break;
			}
			String imdb_name = null;
			int pid = 0;
			String first_name;
			String last_name;
			for (String line : lines) {
				String title;
				
				if (imdb_name == null) {
					String parts[] = line.split("\t+");
					imdb_name = parts[0];
					title = parts[1];
					String names[] = imdb_name.split(",");
					first_name = names[1];
					last_name = names[0];
					try {
						schema.executeUpdate("INSERT IGNORE INTO People (imdb_name,first_name,last_name)"
								+ " VALUES ("
								+ imdb_name
								+ ", "
								+ first_name
								+ ", " + last_name + ")");

						pid = schema
								.getForeignKey("SELECT P.id FROM People as P where P.imdb_name = "
										+ imdb_name);
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						continue;
					}

				} else {
					String parts[] = line.split("\t+");
					title = parts[0];
				}
				Matcher m = title_pat.matcher(title);
				String movie_name = m.group(1);

				try {
					int mid = schema
							.getForeignKey("SELECT M.movie_id from movies as M where "
									+ "M.imdb_name = '" + movie_name + "'");

					batch.add("INSERT IGNORE INTO directors (preson_id,movie_id) VALUES ("
							+ pid
							+ ", "
							+ mid
							);
				} catch (SQLException e) {
					// TODO Auto-generated catch block
					continue;
				}
			}
		}
		batch.close();
	}

	public void import_bios(String filename) throws IOException, SQLException {
		ListFileParser parser = new ListFileParser(filename);
		parser.skipUntil("^BIOGRAPHY\\s+LIST\\s*$");
		// parser.skipUntil("^-*\\s+-*\\s*$");
		parser.skipUntil("^=+\\s*$");
		parser.readLine();

		while (true) {
			int pid;
			try {
				List<String> lines = parser.readUntil("^\\s*$");
				;
				// List<String> lines =
				// parser.readUntil("---------------------------");
				// parser.readUntil("^\\s*$");
				if (lines == null)
					break;
				int i = 0;
				// System.out.println(lines.get(0));
				// System.out.println(lines);
				// String paragraph =
				for (String line : lines) {
					int b_year = 0;
					int d_year = 0;
					String NM = null;
					String RN = null;
					String DD = null;
					String DB = null;
					String NK = null;

					if (line.startsWith("\\s\\s") || (line.startsWith("--")))
						continue;

					if (line.startsWith("NM")) {
						NM = line.substring(3);
						pid = schema
								.getForeignKey("SELECT P.id FROM People as P where P.imdb_name = "
										+ NM);
					}
					if (line.startsWith("RN")) {
						RN = line.substring(3);

					}
					if (line.startsWith("DB")) {
						DB = line.substring(3);
						b_year = search_in_line(DB);

					}
					if (line.startsWith("NK")) {
						NK = line.substring(3);

					}
					if (line.startsWith("DD")) {
						DD = line.substring(3);
						d_year = search_in_line(DD);
					}

					System.out.println(NM + " " + RN + " " + NK + " " + b_year
							+ " " + d_year);
					// System.out.println(year);
				}

			} catch (EOFException e) {
				break;
			}
		}
	}

	private int search_in_line(String line) {
		Pattern p = Pattern.compile("(.*\\w+)\\s(\\d{4})(.*$)");
		Matcher m = p.matcher(line);
		if (m.matches())
			return Integer.parseInt(m.group(2));
		return -1;
	}

	public void import_ratings(String filename) throws IOException,
			SQLException {
		final Pattern line_pat = Pattern
				.compile("\\s+(\\d+)\\s+(\\d+)\\s+(.*\\d+\\s+)\\s+(.*)$");
		ListFileParser parser = new ListFileParser(filename);
		parser.skipUntil("^New\\s+Distribution\\s+Votes\\s+Rank\\s+Title*$");
		Batch batch = schema.createBatch();

		while (true) {
			String line = parser.readLine();
			if (line == null) {
				break;
			}
			Matcher m = line_pat.matcher(line);
			if (!m.matches()) {
				continue;
			}
			String full_name = m.group(4);
			int votes = Integer.parseInt(m.group(2));
			double rating = Double.parseDouble(m.group(3));
			int mid;
			try {
				mid = schema
						.getForeignKey("select M.movie_id from Movies as M "
								+ "where M.imdb_name = '" + full_name + "'");
			} catch (SQLException e) {
				continue;
			}
			batch.add("UPDATE Movies SET rating = " + rating + ", votes = "
					+ votes + " WHERE movie_id = " + mid);
		}
		batch.close();
	}
}