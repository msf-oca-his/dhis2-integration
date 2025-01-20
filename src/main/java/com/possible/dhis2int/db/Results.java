package com.possible.dhis2int.db;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Results {

	List<Map<String, Object>> results = new ArrayList<>();

	public static Results create(ResultSet resultSet) throws SQLException {
		Results response = new Results();
		ResultSetMetaData metaData = resultSet.getMetaData();
        int numberOfCols = metaData.getColumnCount();

        while (resultSet.next()) {
            Map<String, Object> row = new HashMap<>();
            for (int colIndex = 1; colIndex <= numberOfCols; colIndex++) {
                String columnName = metaData.getColumnName(colIndex);
                Object value = resultSet.getObject(colIndex);
                row.put(columnName, value);
            }
            response.results.add(row);
        }
        return response;
	}

    public List<Map<String, Object>> getResults() {
        return results;
    }

    public Map<String, Object> findRowByKeyValue(String key, String value) {
        for (Map<String, Object> row : results) {
            if (row.containsKey(key) && value.equalsIgnoreCase(row.get(key).toString())) {
                return row;
            }
        }
        return null;
    }
}
