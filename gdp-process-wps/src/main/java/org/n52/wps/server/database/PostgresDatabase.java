package org.n52.wps.server.database;

import com.google.common.base.Joiner;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.naming.NamingException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.n52.wps.ServerDocument;
import org.n52.wps.commons.PropertyUtil;
import org.n52.wps.commons.WPSConfig;
import static org.n52.wps.server.database.AbstractDatabase.getDatabaseProperties;
import org.n52.wps.server.database.connection.ConnectionHandler;
import org.n52.wps.server.database.connection.DefaultConnectionHandler;
import org.n52.wps.server.database.connection.JNDIConnectionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author isuftin
 */
public class PostgresDatabase extends AbstractDatabase {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresDatabase.class);
    
    private static final String DEFAULT_ENCODING = "UTF-8";

    private static final String KEY_DATABASE_ROOT = "org.n52.wps.server.database";
    private static final String KEY_DATABASE_PATH = "path";
    private static final String KEY_DATABASE_WIPE_ENABLED = "wipe.enabled";
    private static final String KEY_DATABASE_WIPE_PERIOD = "wipe.period";
    private static final String KEY_DATABASE_WIPE_THRESHOLD = "wipe.threshold";
    private static final boolean DEFAULT_DATABASE_WIPE_ENABLED = true;
    private static final long DEFAULT_DATABASE_WIPE_PERIOD = 1000 * 60 * 60; // default to running once an hour
    private static final long DEFAULT_DATABASE_WIPE_THRESHOLD = 1000 * 60 * 60 * 24 * 7; // default to wipe things over a week old

    private static final String FILE_URI_PREFIX = "file://";
    private static final String SUFFIX_GZIP = "gz";
    private static final String DEFAULT_BASE_DIRECTORY
            = Joiner.on(File.separator).join(System.getProperty("java.io.tmpdir", "."), "Database", "Results");
    private static final ServerDocument.Server server = WPSConfig.getInstance().getWPSConfig().getServer();
    private static final String baseResultURL = String.format("http://%s:%s/%s/RetrieveResultServlet?id=",
            server.getHostname(), server.getHostport(), server.getWebappPath());

    private static final int SELECTION_STRING_REQUEST_ID_PARAM_INDEX = 1;
    private static final int SELECTION_STRING_RESPONSE_COLUMN_INDEX = 1;
    private static final int SELECTION_STRING_RESPONSE_MIMETYPE_COLUMN_INDEX = 2;

    private static String connectionURL;
    private static Path BASE_DIRECTORY;
    private static PostgresDatabase instance;
    private static ConnectionHandler connectionHandler;
private final static  boolean SAVE_RESULTS_TO_DB = Boolean.parseBoolean(getDatabaseProperties("saveResultsToDB"));
protected final Object storeResponseLock = new Object();

    private static Timer wipeTimer;
	private final String DATABASE_NAME;
	
    private static final String CREATE_RESULTS_TABLE_PSQL
            = "CREATE TABLE RESULTS ("
            + "REQUEST_ID VARCHAR(100) NOT NULL PRIMARY KEY, "
            + "REQUEST_DATE TIMESTAMP, "
            + "RESPONSE_TYPE VARCHAR(100), "
            + "RESPONSE TEXT, "
            + "RESPONSE_MIMETYPE VARCHAR(100))";
	
    private PostgresDatabase() {
		PropertyUtil propertyUtil = new PropertyUtil(server.getDatabase().getPropertyArray(), KEY_DATABASE_ROOT);
		String baseDirectoryPath = propertyUtil.extractString(KEY_DATABASE_PATH, DEFAULT_BASE_DIRECTORY);
		String dbName = getDatabaseProperties(PROPERTY_NAME_DATABASE_NAME);
		DATABASE_NAME = (StringUtils.isBlank(dbName)) ? "wps" : dbName;
		try {	
			Class.forName("org.postgresql.Driver");
			initializeBaseDirectory(baseDirectoryPath);
			initializeConnectionHandler();
			initializeResultsTable();
			initializeDatabaseWiper(propertyUtil);
		} catch (IOException | SQLException | NamingException ex) {
			LOGGER.error("Error creating PostgresDatabase", ex);
			throw new RuntimeException("Error creating PostgresDatabase", ex);
		} catch (ClassNotFoundException ex) {
			LOGGER.error("The database class could not be loaded.", ex);
			throw new UnsupportedDatabaseException("The database class could not be loaded.", ex);
		} 
	}

    private void initializeBaseDirectory(final String baseDirectoryPath) throws IOException {
        BASE_DIRECTORY = Paths.get(baseDirectoryPath);
        LOGGER.info("Using \"{}\" as base directory for results database", baseDirectoryPath);
        Files.createDirectories(BASE_DIRECTORY);
    }

    private void initializeDatabaseWiper(PropertyUtil propertyUtil) {
        if (propertyUtil.extractBoolean(KEY_DATABASE_WIPE_ENABLED, DEFAULT_DATABASE_WIPE_ENABLED)) {
            long periodMillis = propertyUtil.extractPeriodAsMillis(KEY_DATABASE_WIPE_PERIOD, DEFAULT_DATABASE_WIPE_PERIOD);
            long thresholdMillis = propertyUtil.extractPeriodAsMillis(KEY_DATABASE_WIPE_THRESHOLD, DEFAULT_DATABASE_WIPE_THRESHOLD);
            wipeTimer = new Timer(PostgresDatabase.class.getSimpleName() + " Postgres Wiper", true);
            wipeTimer.scheduleAtFixedRate(new PostgresDatabase.WipeTimerTask(thresholdMillis), 15000, periodMillis);
            LOGGER.info("Started {} Postgres wiper timer; period {} ms, threshold {} ms",
                    new Object[]{DATABASE_NAME, periodMillis, thresholdMillis});
        } else {
            wipeTimer = null;
        }
    }

    private void initializeConnectionHandler() throws SQLException, NamingException {
        String jndiName = getDatabaseProperties("jndiName");
        if (null != jndiName) {
            connectionHandler = new JNDIConnectionHandler(jndiName);
        } else {
            connectionURL = "jdbc:postgresql:" + getDatabasePath() + "/" + DATABASE_NAME;
            LOGGER.debug("Database connection URL is: " + connectionURL);
            String username = getDatabaseProperties("username");
            String password = getDatabaseProperties("password");
            Properties props = new Properties();
            props.setProperty("create", "true");
            props.setProperty("user", username);
            props.setProperty("password", password);
            connectionHandler = new DefaultConnectionHandler(connectionURL, props);
        }
    }

	private void initializeResultsTable() throws SQLException {
		try (Connection connection = connectionHandler.getConnection();
			ResultSet rs = getTables(connection)) {
			if (!rs.next()) {
				LOGGER.debug("Table RESULTS does not yet exist, creating it.");
				try (Statement st = connection.createStatement()) {
					st.executeUpdate(CREATE_RESULTS_TABLE_PSQL);
				}
			}
		}
	}

    public static synchronized PostgresDatabase getInstance() {
        if (instance == null) {
            instance = new PostgresDatabase();
        }
        return instance;
    }

    @Override
    public String getConnectionURL() {
        return connectionURL;
    }

    @Override
    public Connection getConnection() {
        try {
            return connectionHandler.getConnection();
        } catch (SQLException ex) {
            throw new RuntimeException("Unable to obtain connection to database!", ex);
        }
    }
	
	private ResultSet getTables(Connection connection) throws SQLException {
		return connection.getMetaData().getTables(null, null, "results", new String[]{"TABLE"});
	}

    @Override
    public String generateRetrieveResultURL(String id) {
        return baseResultURL + id;
    }

    @Override
    public void insertRequest(String id, InputStream inputStream, boolean xml) {
        insertResultEntity(inputStream, "REQ_" + id, "ExecuteRequest", xml ? "text/xml" : "text/plain");
    }

    @Override
    public String insertResponse(String id, InputStream inputStream) {
        return insertResultEntity(inputStream, id, "ExecuteResponse", "text/xml");
    }
    
	@Override
	protected String insertResultEntity(InputStream stream, String id, String type, String mimeType) {
		boolean compressData = !SAVE_RESULTS_TO_DB;
		boolean proceed = true;
		String data = "";
		synchronized (storeResponseLock) {
			if (!SAVE_RESULTS_TO_DB) {
				try {
				// The result contents won't be saved to the database, only a pointer to the file system. I am therefore
					// going to GZip the data to save space
					data = writeInputStreamToDisk(id, stream, compressData);
				} catch (IOException ex) {
					LOGGER.error("Failed to write output data to disk", ex);
					proceed = false;
				}
			}

			if (proceed) {
				try (Connection connection = getConnection();
						PreparedStatement insertStatement = connection.prepareStatement(insertionString)) {

					insertStatement.setString(INSERT_COLUMN_REQUEST_ID, id);
					insertStatement.setTimestamp(INSERT_COLUMN_REQUEST_DATE, new Timestamp(Calendar.getInstance().getTimeInMillis()));
					insertStatement.setString(INSERT_COLUMN_RESPONSE_TYPE, type);
					insertStatement.setString(INSERT_COLUMN_MIME_TYPE, mimeType);

					if (SAVE_RESULTS_TO_DB) {
					// This is implemented because we need to handle the case of SAVE_RESULTS_TO_DB = true. However,
						// this should not be used if you expect results to be large. 
						// TODO- Remove and reimplement when setAsciiStream() has been properly implemented 
						// @ https://github.com/pgjdbc/pgjdbc/blob/master/org/postgresql/jdbc4/AbstractJdbc4Statement.java
						insertStatement.setString(INSERT_COLUMN_RESPONSE, IOUtils.toString(stream, DEFAULT_ENCODING));
					} else {
						insertStatement.setString(INSERT_COLUMN_RESPONSE, data);
					}
					insertStatement.executeUpdate();
					LOGGER.debug(MessageFormat.format("Inserted data into database with id of:{0}, type of: {1}, mimetype of: {2}", id, type, mimeType));
				} catch (SQLException | IOException ex) {
					LOGGER.error(MessageFormat.format("Failed to insert data into database with  id of:{0}, type of: {1}, mimetype of: {2}", id, type, mimeType), ex);
				}
			}
		}
		return generateRetrieveResultURL(id);
	}

	
	/**
	 * Writes an input stream to disk
	 * @param filename base filename
	 * @param data String of data to write to disk, compressed using gzip
	 * @param compress true to GZip results
	 * @return String of the file URI pointing where the data was written
	 * @throws Exception
	 */
	private String writeInputStreamToDisk(String filename, InputStream data, boolean compress) throws IOException {
		Path filePath = BASE_DIRECTORY.resolve(Joiner.on(".").join(filename, SUFFIX_GZIP));
		Files.deleteIfExists(filePath);
		Path createdFilePath = Files.createFile(filePath);
		
		OutputStream os = new FileOutputStream(createdFilePath.toFile());
		
		if (compress) {
			os = new GZIPOutputStream(os);
		}
		
		IOUtils.copyLarge(data, os);
		IOUtils.closeQuietly(os);
		return createdFilePath.toUri().toString().replaceFirst(FILE_URI_PREFIX, "");
	}

	@Override
	public void updateResponse(String id, InputStream stream) {
		boolean compressData = !SAVE_RESULTS_TO_DB;
		boolean proceed = true;
		String data = "";

		synchronized (storeResponseLock) {
			if (!SAVE_RESULTS_TO_DB) {
				try {
			// The result contents won't be saved to the database, only a pointer to the file system. I am therefore
					// going to GZip the data to save space
					data = writeInputStreamToDisk(id, stream, compressData);
				} catch (IOException ex) {
					LOGGER.error("Failed to write output data to disk", ex);
					proceed = false;
				}
			}

			if (proceed) {
				try (Connection connection = getConnection();
						PreparedStatement updateStatement = connection.prepareStatement(updateString)) {
					updateStatement.setString(INSERT_COLUMN_REQUEST_ID, id);
					updateStatement.setTimestamp(INSERT_COLUMN_REQUEST_DATE, new Timestamp(Calendar.getInstance().getTimeInMillis()));

					if (SAVE_RESULTS_TO_DB) {
						// This is implemented because we need to handle the case of SAVE_RESULTS_TO_DB = true. However,
						// this should not be used if you expect results to be large. 
						// TODO- Remove and reimplement when setAsciiStream() has been properly implemented 
						// @ https://github.com/pgjdbc/pgjdbc/blob/master/org/postgresql/jdbc4/AbstractJdbc4Statement.java
						updateStatement.setString(INSERT_COLUMN_RESPONSE, IOUtils.toString(stream, DEFAULT_ENCODING));
					} else {
						updateStatement.setString(INSERT_COLUMN_RESPONSE, data);
					}
					updateStatement.executeUpdate();

					LOGGER.debug("Updated data  into database with id of:" + id);
				} catch (SQLException | IOException ex) {
					LOGGER.error(MessageFormat.format("Failed to update data in database with  id of:{0}", id), ex);
				}
			}
		}
	}

	@Override
	public InputStream lookupResponse(String id) {
		InputStream result = null;
		synchronized (storeResponseLock) {
			if (StringUtils.isNotBlank(id)) {
				try (Connection connection = getConnection();
						PreparedStatement selectStatement = connection.prepareStatement(selectionString)) {
					selectStatement.setString(SELECTION_STRING_REQUEST_ID_PARAM_INDEX, id);

					try (ResultSet rs = selectStatement.executeQuery()) {
						if (null == rs || !rs.next()) {
							LOGGER.warn("No response found for request id " + id);
						} else {
							result = rs.getAsciiStream(SELECTION_STRING_RESPONSE_COLUMN_INDEX);
						// Copy the file to disk and create an inputstream from that because once I leave
							// this function, result will not be accessible since the connection to the database 
							// will be broken. I eat a bit of overhead this way, but afaik, it's the best solution
							File tempFile = Files.createTempFile("GDP-SAFE-TO-DELETE-" + id, null).toFile();

							// Best effort, even though SelfCleaningFileInputStream should delete it
							tempFile.deleteOnExit();

							// Copy the ASCII stream to file
							IOUtils.copyLarge(result, new FileOutputStream(tempFile));
							IOUtils.closeQuietly(result);

							// Create an InputStream (of the self-cleaning type) from this File and pass that on
							result = new SelfCleaningFileInputStream(tempFile);
						}
					} catch (IOException ex) {
						LOGGER.error("Could not look up response in database", ex);
					}
				} catch (SQLException ex) {
					LOGGER.error("Could not look up response in database", ex);
				}

				if (null != result) {
					if (!SAVE_RESULTS_TO_DB) {
						try {
							String outputFileLocation = IOUtils.toString(result);
							LOGGER.debug("ID {} is output and saved to disk instead of database. Path = " + outputFileLocation);
							if (Files.exists(Paths.get(outputFileLocation))) {
								result = new GZIPInputStream(new FileInputStream(outputFileLocation));
							} else {
								LOGGER.warn("Response not found on disk for id " + id + " at " + outputFileLocation);
							}
						} catch (FileNotFoundException ex) {
							LOGGER.warn("Response not found on disk for id " + id, ex);
						} catch (IOException ex) {
							LOGGER.warn("Error processing response for id " + id, ex);
						}
					}
				} else {
					LOGGER.warn("response found but returned null");
				}

			} else {
				LOGGER.warn("tried to look up response for null id, returned null");
			}
		}
		return result;
	}

    @Override
    public String getMimeTypeForStoreResponse(String id) {
        String mimeType = null;
        try (Connection connection = getConnection(); PreparedStatement selectStatement = connection.prepareStatement(selectionString)) {
            selectStatement.setString(SELECTION_STRING_REQUEST_ID_PARAM_INDEX, id);
            try (ResultSet rs = selectStatement.executeQuery()) {
                if (null == rs || !rs.next()) {
                    LOGGER.warn("No response found for request id " + id);
                } else {
                    mimeType = rs.getString(SELECTION_STRING_RESPONSE_MIMETYPE_COLUMN_INDEX);
                }
            }
        } catch (SQLException ex) {
            LOGGER.error("Could not look up response in database", ex);
        }
        return mimeType;
    }

	@Override
	public File lookupResponseAsFile(String id) {
		if (!SAVE_RESULTS_TO_DB) {
			synchronized (storeResponseLock) {
				try {
					String outputFileLocation = IOUtils.toString(lookupResponse(id));
					return new File(new URI(outputFileLocation));
				} catch (URISyntaxException | IOException ex) {
					LOGGER.warn("Could not get file location for response file for id " + id, ex);
				}
			}
		}
		LOGGER.warn("requested response as file for a response stored in the database, returning null");
		return null;
	}
	
    private class WipeTimerTask extends TimerTask {

        private final long thresholdMillis;
        private static final String DELETE_STATEMENT = "DELETE FROM RESULTS WHERE RESULTS.REQUEST_ID = ANY ( ? ) AND RESULTS.REQUESTS_ID NOT LIKE 'REQ_%';";
        private static final int DELETE_STATEMENT_LIST_PARAM_INDEX = 1;
        private static final String LOOKUP_STATEMENT = "SELECT * FROM "
                + "(SELECT REQUEST_ID, EXTRACT(EPOCH FROM REQUEST_DATE) * 1000 AS TIMESTAMP FROM RESULTS) items WHERE TIMESTAMP < ?";
        private static final int LOOKUP_STATEMENT_TIMESTAMP_PARAM_INDEX = 1;
        private static final int LOOKUP_STATEMENT_REQUEST_ID_COLUMN_INDEX = 1;
	private final String databaseName = getDatabaseName();

        WipeTimerTask(long thresholdMillis) {
            this.thresholdMillis = thresholdMillis;
        }

        @Override
        public void run() {
            LOGGER.info(databaseName + " Postgres wiper, checking for records older than {} ms", thresholdMillis);
            try {
				
                int deletedRecordsCount = wipe();
                if (deletedRecordsCount > 0) {
                    LOGGER.info(databaseName + " Postgres wiper, cleaned {} records from database", deletedRecordsCount);
                } else {
                    LOGGER.debug(databaseName + " Postgres wiper, cleaned {} records from database", deletedRecordsCount);
                }
            } catch (SQLException | IOException ex) {
                LOGGER.warn(databaseName + " Postgres wiper, failed to deleted old records", ex);
            }
        }

        private int wipe() throws SQLException, IOException {
            LOGGER.debug(databaseName + " Postgres wiper, checking for records older than {} ms", thresholdMillis);
            int deletedRecordsCount = 0;
            List<String> oldRecords = findOldRecords();
            if (!SAVE_RESULTS_TO_DB) {
                for (String recordId : oldRecords) {
                    if (recordId.toLowerCase(Locale.US).contains("output")) {
                        Files.deleteIfExists(Paths.get(BASE_DIRECTORY.toString(), recordId));
                    }
                }
            }
            if (!oldRecords.isEmpty()) {
                deletedRecordsCount = deleteRecords(oldRecords);
            }
            return deletedRecordsCount;
        }

        private int deleteRecords(List<String> recordIds) throws SQLException {
            int deletedRecordsCount;
            try (Connection connection = connectionHandler.getConnection(); PreparedStatement deleteStatement = connection.prepareStatement(DELETE_STATEMENT)) {
                deleteStatement.setArray(DELETE_STATEMENT_LIST_PARAM_INDEX, connection.createArrayOf("varchar", recordIds.toArray()));
                deletedRecordsCount = deleteStatement.executeUpdate();
            }
            return deletedRecordsCount;
        }

        private List<String> findOldRecords() throws SQLException {
            List<String> matchingRecords = new ArrayList<>();
            try (Connection connection = connectionHandler.getConnection(); PreparedStatement lookupStatement = connection.prepareStatement(LOOKUP_STATEMENT)) {
                long ageMillis = System.currentTimeMillis() - thresholdMillis;
                lookupStatement.setLong(LOOKUP_STATEMENT_TIMESTAMP_PARAM_INDEX, ageMillis);
                try (ResultSet rs = lookupStatement.executeQuery()) {
                    while (rs.next()) {
                        matchingRecords.add(rs.getString(LOOKUP_STATEMENT_REQUEST_ID_COLUMN_INDEX));
                    }
                }
            }
            return matchingRecords;
        }
    }
}
