package com.possible.dhis2int.web;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import static java.lang.String.format;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import static org.apache.log4j.Logger.getLogger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.possible.dhis2int.Properties;
import com.possible.dhis2int.audit.Recordlog;
import com.possible.dhis2int.audit.Submission;
import com.possible.dhis2int.audit.Submission.Status;
import static com.possible.dhis2int.audit.Submission.Status.Failure;
import com.possible.dhis2int.audit.SubmissionLog;
import com.possible.dhis2int.audit.SubmittedDataStore;
import com.possible.dhis2int.date.DateConverter;
import com.possible.dhis2int.date.ReportDateRange;
import com.possible.dhis2int.db.DatabaseDriver;
import com.possible.dhis2int.db.Results;
import com.possible.dhis2int.dhis.DHISClient;
import com.possible.dhis2int.exception.NotAvailableException;
import static com.possible.dhis2int.web.Cookies.BAHMNI_USER;
import static com.possible.dhis2int.web.Messages.CONFIG_FILE_NOT_FOUND;
import static com.possible.dhis2int.web.Messages.DHIS_SUBMISSION_FAILED;
import static com.possible.dhis2int.web.Messages.FILE_READING_EXCEPTION;
import static com.possible.dhis2int.web.Messages.REPORT_DOWNLOAD_FAILED;

@RestController
public class DHISIntegrator {

	private final Logger logger = getLogger(DHISIntegrator.class);

	private final String DOWNLOAD_FORMAT = "application/vnd.ms-excel";

	private final String SUBMISSION_ENDPOINT = "/api/dataValueSets";

	private final String DHIS_GET_URL = "dhis-web-dataentry/getDataValues.action";

	private final DHISClient dHISClient;

	private final DatabaseDriver databaseDriver;

	private final Properties properties;

	private final SubmissionLog submissionLog;

	private final SubmittedDataStore submittedDataStore;

	private final String IMAM_PROGRAM_NAME = "03-2_Nutrition_Acute_Malnutrition";
	private final String IMAM = "Integrated Management of Acute Malnutrition (IMAM) Program";

	private final String FamilyPlanning_PROGRAM_NAME = "07-Family_Planning_Program";
	private final String Family = "Family Planning Program all temporary methods";

	@Autowired
	public DHISIntegrator(DHISClient dHISClient, DatabaseDriver databaseDriver, Properties properties,
			SubmissionLog submissionLog, SubmittedDataStore submittedDataStore) {
		this.dHISClient = dHISClient;
		this.databaseDriver = databaseDriver;
		this.properties = properties;
		this.submissionLog = submissionLog;
		this.submittedDataStore = submittedDataStore;
	}

	@RequestMapping(path = "/is-logged-in")
	public String isLoggedIn() {
		logger.info("Inside isLoggedIn");
		return "Logged in";
	}

	@RequestMapping(path = "/hasReportingPrivilege")
	public Boolean hasReportSubmissionPrivilege(HttpServletRequest request, HttpServletResponse response) {
		return dHISClient.hasDhisSubmitPrivilege(request, response);
	}

	public void prepareImamReport(Integer year, Integer month) throws JSONException {
		logger.info("Inside prepareImamReport method");

		JSONObject dhisConfig = (JSONObject) getDHISConfig(IMAM_PROGRAM_NAME);
		String orgUnit = (String) dhisConfig.get("orgUnit");
		String imamDataSetId = (String) dhisConfig.get("dataSetIdImam");

		Integer prevMonth;
		if (month == 1) {
			year -= 1;
			prevMonth = 12;
		} else {
			prevMonth = month - 1;
		}
		String previousMonth = prevMonth < 10 ? String.format("%02d", prevMonth) : String.format("%2d", prevMonth);
		StringBuilder dhisRequestUrl = new StringBuilder(DHIS_GET_URL);
		dhisRequestUrl.append("?dataSetId=").append(imamDataSetId).append("&organisationUnitId=").append(orgUnit)
				.append("&multiOrganisationUnit=false&").append("periodId=").append(year).append(previousMonth);

		ResponseEntity<String> response = dHISClient.get(dhisRequestUrl.toString());
		JSONObject jsonResponse = new JSONObject(response.getBody().toString());

		dhisConfig = (JSONObject) dhisConfig.get("reports");

		JSONArray dataValues = new JSONArray();
		dataValues = dhisConfig.getJSONObject(IMAM).getJSONArray("dataValues");
		JSONArray fieldsFromDhis = new JSONArray();

		JSONArray dhisDataSet = jsonResponse.getJSONArray("dataValues");
		Map<String, Integer> valuesFromDhis = new HashMap<>();

		for (Object dataValue_ : jsonArrayToList(dataValues)) {
			JSONObject dataValue = (JSONObject) dataValue_;
			if (dataValue.has("getElementBack") && dataValue.get("getElementBack") != null
					&& (Boolean) dataValue.get("getElementBack")) {
				String id = new StringBuilder().append(dataValue.get("dataElement")).append("-")
						.append(dataValue.get("categoryOptionCombo")).toString();

				for (Object dataVa_ : jsonArrayToList(dhisDataSet)) {
					JSONObject dataVal = (JSONObject) dataVa_;
					if (dataVal.get("id").equals(id)) {
						dataValue.put("value", dataVal.get("val"));
						fieldsFromDhis.put(dataValue);
						valuesFromDhis.put(dataValue.getString("fieldValue"),
								Integer.parseInt((String) dataVal.get("val")));
					}
				}

			}
		}

		Integer numberOfMaleLessThanSix = valuesFromDhis.get("numberOfMaleLessThanSix") != null
				? valuesFromDhis.get("numberOfMaleLessThanSix")
				: 0;
		Integer numberOfFemalesLessThanSix = valuesFromDhis.get("numberOfFemalesLessThanSix") != null
				? valuesFromDhis.get("numberOfFemalesLessThanSix")
				: 0;
		Integer numberOfMalesMoreThanSix = valuesFromDhis.get("numberOfMalesMoreThanSix") != null
				? valuesFromDhis.get("numberOfMalesMoreThanSix")
				: 0;
		Integer numberOfFemalesMoreThanSix = valuesFromDhis.get("numberOfFemalesMoreThanSix") != null
				? valuesFromDhis.get("numberOfFemalesMoreThanSix")
				: 0;

		databaseDriver.createTempTable(numberOfMaleLessThanSix, numberOfFemalesLessThanSix, numberOfMalesMoreThanSix,
				numberOfFemalesMoreThanSix);

	}

	@RequestMapping(path = "/submit-to-dhis")
	public List<Map<String, Object>> submitToDHIS(
			@RequestParam("name") String program, @RequestParam("year") Integer year,
			@RequestParam(value = "month", required = false) Integer month,
			@RequestParam(value = "week", required = false) Integer week,
			@RequestParam("comment") String comment, HttpServletRequest clientReq, HttpServletResponse clientRes)
			throws IOException, JSONException {

		// Check if either month or week is provided, but not both
		if (month == null && week == null) {
			throw new IllegalArgumentException("Either 'month' or 'week' must be provided.");
		};

		if (month != null && week != null) {
			throw new IllegalArgumentException("Provide only one: either 'month' or 'week', not both.");
		};

		String userName = new Cookies(clientReq).getValue(BAHMNI_USER);
		JSONObject dhisLocationConfig = getDHISConfig("locations");
		JSONArray locations = dhisLocationConfig.getJSONArray("locations");
		List<Map<String, Object>> submissionInfos = new ArrayList<>();

		for (int i = 0; i < locations.length(); i++) {
			JSONObject location = locations.getJSONObject(i);
			Submission submission = new Submission();
			String filePath = submittedDataStore.getAbsolutePath(submission);
			Status status;

			try {
					submitToDHIS(submission, program, year, month, week, location.getString("name"));
					status = submission.getStatus();
			} catch (DHISIntegratorException | JSONException e) {
					status = Failure;
					submission.setException(e);
					logger.error(DHIS_SUBMISSION_FAILED, e);
			} catch (Exception e) {
					status = Failure;
					submission.setException(e);
					logger.error(Messages.INTERNAL_SERVER_ERROR, e);
			}

			submittedDataStore.write(submission);
			submissionLog.log(program, userName, comment, status, filePath);

			String period;
			if (month != null) {
					period = format("%dM%d", year, month);
			} else {
					period = format("%dW%d", year, week);
			}

			recordLog(userName, program, year, period, submission.toStrings(), status, comment, location.getString("name"));

			Map<String, Object> submissionInfo = submission.getInfo().toMap();
			if (status != Status.NoData) {
				submissionInfos.add(submissionInfo);
			}
    }

    return submissionInfos;
	}

	@RequestMapping(path = "/submit-to-dhis_report_status")
	public String submitToDHISLOG(
			@RequestParam("name") String program,
			@RequestParam("year") Integer year,
			@RequestParam(value = "month", required = false) Integer month,
			@RequestParam(value = "week", required = false) Integer week,
			@RequestParam("comment") String comment, HttpServletRequest clientReq,
			HttpServletResponse clientRes) throws IOException, JSONException {
		String userName = new Cookies(clientReq).getValue(BAHMNI_USER);
		Submission submission = new Submission();
		Status status;
		try {
			submitToDHIS(submission, program, year, month, null, null);
			status = submission.getStatus();
		} catch (DHISIntegratorException | JSONException e) {
			status = Failure;
			submission.setException(e);
			logger.error(DHIS_SUBMISSION_FAILED, e);
		} catch (Exception e) {
			status = Failure;
			submission.setException(e);
			logger.error(Messages.INTERNAL_SERVER_ERROR, e);
		}
		submittedDataStore.write(submission);

		String period;
		if (month != null) {
			period = format("%dW%d", year, month);
		} else {
			period = format("%dW%d", year, week);
		}


		recordLog(userName, program, year, period, submission.toStrings(), status, comment, null);
		return submission.toStrings();
	}

	private String recordLog(String userName, String program, Integer year, String period, String log, Status status,
			String comment, String location) throws IOException, JSONException {
		Date date = new Date();
		Status submissionStatus = status;
		if (status == Status.Failure) {
			submissionStatus = Status.Incomplete;
		} else if (status == Status.Success) {
			submissionStatus = Status.Complete;
		}
		Recordlog recordLog = new Recordlog(program, date, userName, log, submissionStatus, comment, location);
		databaseDriver.recordQueryLog(recordLog, period, year);
		return "Saved";
	}

	@RequestMapping(path = "/log")
	public String getLog(@RequestParam String programName,
						 @RequestParam("year") Integer year,
						 @RequestParam(value = "month", required = false) Integer month,
						 @RequestParam(value = "week", required = false) Integer week) throws SQLException {
		logger.info("Inside getLog method");

		String period;
		if (month != null) {
			period = format("%dW%d", year, month);
		} else {
			period = format("%dW%d", year, week);
		}

		return databaseDriver.getQuerylog(programName, period, year);
	}

	// @RequestMapping(path = "/submit-to-dhis-atr")
	// public String submitToDhisAtrOptCombo(@RequestParam("name") String program,
	// 									  @RequestParam("year") Integer year,
	// 									  @RequestParam(value = "month", required = false) Integer month,
	// 									  @RequestParam(value = "week", required = false) Integer week, @RequestParam("comment") String comment, HttpServletRequest clientReq,
	// 		HttpServletResponse clientRes) throws IOException, JSONException {
	// 	String userName = new Cookies(clientReq).getValue(BAHMNI_USER);
	// 	Submission headSubmission = new Submission();
	// 	String filePath = submittedDataStore.getAbsolutePath(headSubmission);
	// 	List<Submission> batchSubmission = new ArrayList<>();
	// 	Results results;
	// 	int i = 0;
	// 	try {
	// 		JSONObject reportConfig = getConfig(properties.reportsJson);
	// 		List<JSONObject> childReports = jsonArrayToList(
	// 				reportConfig.getJSONObject(program).getJSONObject("config").getJSONArray("reports"));
	// 		String sqlPath = childReports.get(0).getJSONObject("config").getString("sqlPath");
	// 		ReportDateRange dateRange = new DateConverter().getDateRange(year, month);
	// 		String type = "MRSGeneric";

	// 		results = getResult(getContent(sqlPath), type, dateRange);

	// 		for (List<String> row : results.getRows()) {
	// 			Submission submission = new Submission();
	// 			submitToDhisAtrOptCombo(row, submission, program, year, month);
	// 			Status status = submission.getStatus();
	// 			submission.setStatus(status);
	// 			batchSubmission.add(submission);
	// 			i++;
	// 		}

	// 	} catch (DHISIntegratorException | JSONException | SQLException e) {
	// 		if (batchSubmission.size() > 0) {
	// 			batchSubmission.get(i).setStatus(Failure);
	// 			batchSubmission.get(i).setException(e);
	// 		}
	// 		logger.error(e.getMessage(), e);
	// 		headSubmission.setException(e);

	// 	} catch (Exception e) {
	// 		batchSubmission.get(i).setStatus(Failure);
	// 		headSubmission.setException(e);
	// 		logger.error(Messages.INTERNAL_SERVER_ERROR, e);
	// 	} finally {
	// 		Status status = Status.Failure;
	// 		String filePathData = "No Data sent";
	// 		if (batchSubmission.size() > 0) {
	// 			filePathData = filePath;
	// 			submittedDataStore.write(batchSubmission, filePathData);
	// 			status = Status.Success;
	// 			for (Submission submit : batchSubmission) {
	// 				headSubmission = submit;
	// 				if (Status.Failure.equals(submit.retrieveStatus())) {
	// 					status = Failure;
	// 					headSubmission = submit;
	// 					break;
	// 				}
	// 			}
	// 		}
	// 		submissionLog.log(program, userName, comment, status, filePathData);

	// 		String period;
	// 		if (month != null) {
	// 			period = format("%dW%d", year, month);
	// 		} else {
	// 			period = format("%dW%d", year, week);
	// 		}

	// 		recordLog(userName, program, year, period, comment, status, comment);
	// 	}
	// 	return headSubmission.getInfo();
	// }

	// private Submission submitToDhisAtrOptCombo(List<String> row, Submission submission, String name, Integer year,
	// 		Integer month) throws DHISIntegratorException, JSONException, SQLException {
	// 	JSONObject reportConfig = getConfig(properties.reportsJson);

	// 	JSONObject childReport = reportConfig.getJSONObject(name).getJSONObject("config").getJSONArray("reports")
	// 			.getJSONObject(0); // TODO: why always 0 ?

	// 	JSONObject dhisConfig = getDHISConfig(name);
	// 	ReportDateRange dateRange = new DateConverter().getDateRange(year, month);
	// 	List<Object> programDataValue = getProgramDataValuesAttrOptCombo(row, childReport,
	// 			dhisConfig.getJSONObject("reports"), dateRange);

	// 	JSONObject programDataValueSet = new JSONObject();
	// 	programDataValueSet.put("dataset", dhisConfig.getString("dataset"));
	// 	programDataValueSet.put("orgUnit", dhisConfig.getString("orgUnit"));
	// 	programDataValueSet.put("dataValues", programDataValue);
	// 	programDataValueSet.put("period", format("%d%02d", year, month));
	// 	programDataValueSet.put("attributeOptionCombo", row.get(row.size() - 1));

	// 	ResponseEntity<String> responseEntity = dHISClient.post(SUBMISSION_ENDPOINT, programDataValueSet);
	// 	submission.setPostedData(programDataValueSet);
	// 	submission.setResponse(responseEntity);
	// 	return submission;
	// }

	@RequestMapping(path = "/submission-log/download", produces = "text/csv")
	public FileSystemResource downloadSubmissionLog(HttpServletResponse response) throws FileNotFoundException {
		response.setHeader("Content-Disposition", "attachment; filename=" + submissionLog.getDownloadFileName());
		return submissionLog.getFile();
	}

	@RequestMapping(path = "/download")
	public void downloadReport(@RequestParam("name") String name, @RequestParam("year") Integer year,
			@RequestParam("month") Integer month, @RequestParam("isImam") Boolean isImam,
			@RequestParam("isFamily") Boolean isFamily, HttpServletResponse response)
			throws JSONException, IOException {
		ReportDateRange reportDateRange = new DateConverter().getDateRange(year, month);
		if (isImam != null && isImam) {
			prepareImamReport(year, month);
			System.out.println("after Imam report ");
		}
		if (isFamily != null && isFamily) {
			prepareFamilyPlanningReport(year, month);
		}
		try {
			String redirectUri = UriComponentsBuilder.fromHttpUrl(properties.reportsUrl)
					.queryParam("responseType", DOWNLOAD_FORMAT).queryParam("name", name)
					.queryParam("startDate", reportDateRange.getStartDate())
					.queryParam("endDate", reportDateRange.getEndDate()).toUriString();
			response.sendRedirect(redirectUri);

		} catch (Exception e) {
			logger.error(format(REPORT_DOWNLOAD_FAILED, name), e);
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

	@RequestMapping(path = "/download/fiscal-year-report")
	public void downloadFiscalYearReport(@RequestParam("name") String name,
			@RequestParam("startYear") Integer startYear, @RequestParam("startMonth") Integer startMonth,
			@RequestParam("endYear") Integer endYear, @RequestParam("endMonth") Integer endMonth,
			@RequestParam("isImam") Boolean isImam, HttpServletResponse response)
			throws JSONException, NotAvailableException {
		logger.info("Inside downloadFiscalYearReport");
		ReportDateRange reportDateRange = new DateConverter().getDateRangeForFiscalYear(startYear, startMonth, endYear,
				endMonth);
		logger.info(reportDateRange);
		if (isImam != null && isImam) {
			// prepareImamReport(startYear, startMonth);
			throw new NotAvailableException("Imam report is not available for fiscal year");
		}
		try {
			String redirectUri = UriComponentsBuilder.fromHttpUrl(properties.reportsUrl)
					.queryParam("responseType", DOWNLOAD_FORMAT).queryParam("name", name)
					.queryParam("startDate", reportDateRange.getStartDate())
					.queryParam("endDate", reportDateRange.getEndDate()).toUriString();
			response.sendRedirect(redirectUri);
		} catch (IOException e) {
			logger.error(format(REPORT_DOWNLOAD_FAILED, name), e);
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

	private Submission submitToDHIS(Submission submission, String name, Integer year,
									Integer month, Integer week, String locationName)
			throws DHISIntegratorException, JSONException, IOException {
		JSONObject reportConfig = getConfig(properties.reportsJson);

		List<JSONObject> childReports = new ArrayList<>();

		if ("ElisGeneric".equalsIgnoreCase(reportConfig.getJSONObject(name).getString("type"))) {
			JSONObject reportObj = new JSONObject();
			reportObj.put("name", reportConfig.getJSONObject(name).getString("name"));
			reportObj.put("type", reportConfig.getJSONObject(name).getString("type"));

			JSONObject configObj = new JSONObject();
			configObj.put("sqlPath", reportConfig.getJSONObject(name).getJSONObject("config").get("sqlPath"));

			reportObj.put("config", configObj);
			childReports.add(reportObj);

		} else {
			childReports = jsonArrayToList(
					reportConfig.getJSONObject(name).getJSONObject("config").getJSONArray("reports"));
		}

		JSONObject dhisConfig = getDHISConfig(name);

		String orgUnit = getOrgUnit(name, locationName);

		if (orgUnit == null) {
			orgUnit = dhisConfig.getString("orgUnit");
		}

		ReportDateRange dateRange;
		String period;
		if (month != null) {
			dateRange = new DateConverter().getDateRange(year, month);
			period = format("%d%02d", year, month);
		} else {
			org.joda.time.LocalDate startDate = new org.joda.time.LocalDate()
					.withWeekyear(year)
					.withWeekOfWeekyear(week)
					.withDayOfWeek(1);

			org.joda.time.LocalDate endDate = startDate.plusDays(6);

			dateRange = new ReportDateRange(startDate.toDateTimeAtStartOfDay(),
					endDate.toDateTimeAtStartOfDay());
			period = format("%dW%d", year, week);
		}

		List<Object> programDataValue = getProgramDataValues(childReports, dhisConfig.getJSONObject("reports"),
				dateRange, locationName);

		JSONObject programDataValueSet = new JSONObject();
		programDataValueSet.put("orgUnit", orgUnit);
		programDataValueSet.put("dataSet", dhisConfig.getString("dataSet"));
		programDataValueSet.put("dataValues", programDataValue);
		programDataValueSet.put("period", period);

		logger.info("Inside getQueryLog method" + programDataValueSet);

		submission.setLocationName(locationName);
		if (!programDataValue.isEmpty()){
			ResponseEntity<String> responseEntity = dHISClient.post(SUBMISSION_ENDPOINT, programDataValueSet);
			submission.setResponse(responseEntity);
			submission.setPostedData(programDataValueSet);
		} else {
			submission.setResponse(null);
		}

		return submission;
	}

	private  String getOrgUnit(String programName, String locationName) throws DHISIntegratorException{
		JSONObject dhisLocationConfig = getDHISConfig("locations");
		JSONArray locations = dhisLocationConfig.getJSONArray("locations");

		for (int i = 0; i < locations.length(); i++) {
				JSONObject jsonObject = locations.getJSONObject(i);

				if (jsonObject.has("name") && jsonObject.getString("name").equalsIgnoreCase(locationName)) {
					return jsonObject.optString(programName, null);
				}
		}
		return null;
	};

	private JSONObject getConfig(String configFile) throws DHISIntegratorException {
		try {
			return new JSONObject(new JSONTokener(new FileReader(configFile)));
		} catch (FileNotFoundException e) {
			throw new DHISIntegratorException(format(CONFIG_FILE_NOT_FOUND, configFile), e);
		}
	}

	private Results getResult(String sql, String type, ReportDateRange dateRange) throws DHISIntegratorException {
		String formattedSql = sql.replaceAll("#startDate#", dateRange.getStartDate()).replaceAll("#endDate#",
				dateRange.getEndDate());

		return databaseDriver.executeQuery(formattedSql, type);
	}

	private String getContent(String filePath) throws DHISIntegratorException {
		try {
			return Files.readAllLines(Paths.get(filePath)).stream().reduce((x, y) -> x + "\n" + y).get();
		} catch (IOException e) {
			throw new DHISIntegratorException(format(FILE_READING_EXCEPTION, filePath), e);
		}
	}

	private List<Object> getProgramDataValues(List<JSONObject> reportSqlConfigs, JSONObject reportDHISConfigs,
			ReportDateRange dateRange, String locationName) throws DHISIntegratorException, JSONException, IOException {
		ArrayList<Object> programDataValues = new ArrayList<>();

		for (JSONObject report : reportSqlConfigs) {
			JSONArray dataValues = getReportDataElements(reportDHISConfigs, dateRange, report, locationName);
			programDataValues.addAll(jsonArrayToList(dataValues));
		}
		return programDataValues;
	}

	private List<Object> getProgramDataValuesAttrOptCombo(List<String> row, JSONObject childReport,
			JSONObject reportDHISConfigs, ReportDateRange dateRange)
			throws DHISIntegratorException, JSONException, SQLException {

		ArrayList<Object> programDataValues = new ArrayList<>();
		JSONArray dataValues = new JSONArray();
		dataValues = reportDHISConfigs.getJSONObject(childReport.getString("name")).getJSONArray("dataValues");
		for (Object dataValue_ : jsonArrayToList(dataValues)) {
			JSONObject dataValue = (JSONObject) dataValue_;
			updateDataElementsAtrOptCombo(row, dataValue);
		}
		programDataValues.addAll(jsonArrayToList(dataValues));
		return programDataValues;
	}

	private JSONArray getReportDataElements(JSONObject reportDHISConfigs, ReportDateRange dateRange, JSONObject report, String locationName)
			throws DHISIntegratorException, JSONException, IOException {
		JSONArray dataValues = new JSONArray();
		JSONObject reportConfig = reportDHISConfigs.getJSONObject(report.getString("name"));
		JSONObject parametersToUpdate = new JSONObject();

		try {
			parametersToUpdate = reportConfig.getJSONObject("parameters");
		} catch (JSONException e) {
			logger.warn("Not able to fetch variables, Error :" + e);
		}

		try {
			dataValues = reportConfig.getJSONArray("dataValues");
		} catch (JSONException e) {
			throw new DHISIntegratorException(e.getMessage(), e);
		}
		String sqlPath = report.getJSONObject("config").getString("sqlPath");
		String type = report.getString("type");
		String sqlFileContent = getContent(sqlPath);

		// Replace #locationName
		sqlFileContent = sqlFileContent.replaceAll("#locationName#", locationName);

		if (sqlFileContent != null && parametersToUpdate != null) {
			JSONArray parametersNames = parametersToUpdate.names();
			if (parametersNames != null && parametersNames.length() > 0) {
				for (int i = 0; i < parametersNames.length(); i++) {
					String key = parametersNames.getString(i);

					Object valueObject = parametersToUpdate.get(key);
					String replacement;

					if (valueObject instanceof JSONArray) {
						JSONArray valueArray = (JSONArray) valueObject;
						List<String> values = new ArrayList<>();
						for (int j = 0; j < valueArray.length(); j++) {
							values.add(valueArray.getString(j));
						}
						replacement = String.join("\", \"", values);
					} else {
						replacement = (String) valueObject;
					}
					sqlFileContent = sqlFileContent.replaceAll(key, replacement);
				}
			}
		}


		Results results = getResult(sqlFileContent, type, dateRange);

		JSONArray updatedDataValues = new JSONArray();

		for (Object dataValue_ : jsonArrayToList(dataValues)) {
			JSONObject dataValue = (JSONObject) dataValue_;
			JSONObject columnMappings = reportConfig.getJSONObject("columnMappings");

			Map<String, Object> matchingRow = results.findRowByKeyValue(columnMappings.getString("nameColumn"), dataValue.getString("fieldValue"));

			if (matchingRow != null) {
				Object value = matchingRow.get(columnMappings.getString("valueColumn"));
				if (value != null) {
					dataValue.put("value", value);
				}
				dataValue.remove("fieldValue");
				updatedDataValues.put(dataValue);
			}
		}
		return updatedDataValues;
	}

	private List<JSONObject> jsonArrayToList(JSONArray elements) {
		List<JSONObject> list = new ArrayList<>();
		elements.forEach((element) -> list.add((JSONObject) element));
		return list;
	}

	// private void updateDataElements(Results results, JSONObject dataElement) throws JSONException {
	// 	String value = results.get(dataElement.getInt("row"), dataElement.getInt("column"));
	// 	dataElement.put("value", value);
	// }

	private void updateDataElementsAtrOptCombo(List<String> row, JSONObject dataElement) throws JSONException {
		String value = row.get(dataElement.getInt("column") - 1);
		dataElement.put("value", value);
	}

	private JSONObject getDHISConfig(String programName) throws DHISIntegratorException {
		String DHISConfigFile = properties.dhisConfigDirectory + programName.replaceAll(" ", "_") + ".json";
		return getConfig(DHISConfigFile);
	}

	public void prepareFamilyPlanningReport(Integer year, Integer month) throws JSONException {
		logger.info("Inside prepareFamilyPlanningReport method");

		JSONObject dhisConfig = (JSONObject) getDHISConfig(FamilyPlanning_PROGRAM_NAME);
		String orgUnit = (String) dhisConfig.get("orgUnit");
		String familyPlanningDataSetId = (String) dhisConfig.get("dataSetIdFamily");

		Integer prevMonth;
		if (month == 1) {
			year -= 1;
			prevMonth = 12;
		} else {
			prevMonth = month - 1;
		}
		Integer checkdigit = 10;
		String previousMonth = prevMonth < checkdigit ? String.format("%02d", prevMonth)
				: String.format("%2d", prevMonth);
		StringBuilder dhisRequestUrl = new StringBuilder(DHIS_GET_URL);
		dhisRequestUrl.append("?dataSetId=").append(familyPlanningDataSetId).append("&organisationUnitId=")
				.append(orgUnit).append("&multiOrganisationUnit=false&").append("periodId=").append(year)
				.append(previousMonth);

		ResponseEntity<String> response = dHISClient.get(dhisRequestUrl.toString());
		JSONObject jsonResponse = new JSONObject(response.getBody().toString());
		dhisConfig = (JSONObject) dhisConfig.get("reports");
		JSONArray dataValues = new JSONArray();
		dataValues = dhisConfig.getJSONObject(Family).getJSONArray("dataValues");
		JSONArray fieldsFromDhis = new JSONArray();
		JSONArray dhisDataSet = jsonResponse.getJSONArray("dataValues");
		Map<String, Integer> valuesFromDhis = new HashMap<>();

		for (Object dataValue_ : jsonArrayToList(dataValues)) {
			JSONObject dataValue = (JSONObject) dataValue_;
			if (dataValue.has("getElementBack") && dataValue.get("getElementBack") != null
					&& (Boolean) dataValue.get("getElementBack")) {
				String id = new StringBuilder().append(dataValue.get("dataElement")).append("-")
						.append(dataValue.get("categoryOptionCombo")).toString();

				for (Object dataVa_ : jsonArrayToList(dhisDataSet)) {
					JSONObject dataVal = (JSONObject) dataVa_;
					if (id.equals(dataVal.get("id"))) {
						dataValue.put("value", dataVal.get("val"));
						fieldsFromDhis.put(dataValue);
						valuesFromDhis.put(dataValue.getString("fieldValue"),
								Integer.parseInt((String) dataVal.get("val")));

					}
				}

			}
		}

		Integer numberOfVasectomyUser = valuesFromDhis.get("numberOfVasectomyUser") != null
				? valuesFromDhis.get("numberOfVasectomyUser")
				: 0;
		Integer numberOfPillsUser = valuesFromDhis.get("numberOfPillsUser") != null
				? valuesFromDhis.get("numberOfPillsUser")
				: 0;
		Integer numberOfOtherUser = valuesFromDhis.get("numberOfOtherUser") != null
				? valuesFromDhis.get("numberOfOtherUser")
				: 0;
		Integer numberOfMinilipUser = valuesFromDhis.get("numberOfMinilipUser") != null
				? valuesFromDhis.get("numberOfMinilipUser")
				: 0;
		Integer numberOfIUCDUser = valuesFromDhis.get("numberOfIUCDUser") != null
				? valuesFromDhis.get("numberOfIUCDUser")
				: 0;
		Integer numberOfImplantUser = valuesFromDhis.get("numberOfImplantUser") != null
				? valuesFromDhis.get("numberOfImplantUser")
				: 0;
		Integer numberOfDepoUser = valuesFromDhis.get("numberOfDepoUser") != null
				? valuesFromDhis.get("numberOfDepoUser")
				: 0;
		Integer numberOfCondomsUser = valuesFromDhis.get("numberOfCondomsUser") != null
				? valuesFromDhis.get("numberOfCondomsUser")
				: 0;

		databaseDriver.createTempFamilyTable(numberOfVasectomyUser, numberOfPillsUser, numberOfOtherUser,
				numberOfMinilipUser, numberOfIUCDUser, numberOfImplantUser, numberOfDepoUser, numberOfCondomsUser);

	}

}
