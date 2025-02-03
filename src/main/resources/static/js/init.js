const reportConfigUrl = "/bahmni_config/openmrs/apps/reports/reports.json";
const downloadUrl =
  "/dhis-integration/download?name=NAME&year=YEAR&month=MONTH&isImam=IS_IMAM&isFamily=IS_FAMILY";
const submitUrl = "/dhis-integration/submit-to-dhis";
const submitUrlAtr = "/dhis-integration/submit-to-dhis-atr";
const loginRedirectUrl = "/bahmni/home/index.html#/login?showLoginMessage&from=";
const NUTRITION_PROGRAM = "03-2 Nutrition Acute Malnutrition";
const FAMILYPLANNING_PROGRAM = "07 Family Planning Program";
const logUrl = "/dhis-integration/log";
const fiscalYearReportUrl =
  "/dhis-integration/download/fiscal-year-report?name=NAME&startYear=START_YEAR&startMonth=START_MONTH&endYear=END_YEAR&endMonth=END_MONTH&isImam=IS_IMAM";

const supportedYears = {
  startYear: new Date().getFullYear(),
  endYear: new Date().getFullYear() - 10,
};

var spinner = spinner || {};

const months = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
].map((name, index) => ({ number: index + 1, name }));

const weeks = weeksgenerator(10);
var hasReportingPrivilege = false;

$(document).ready(function () {
  isAuthenticated()
    .then(isSubmitAuthorized)
    .then(initTabs)
    .then(renderWeeklyReport)
    // .then(renderPrograms)
    // .then(renderYearlyReport)
    .then(getLogStatus);

  Handlebars.registerHelper("ifEquals", function (arg1, arg2, options) {
    return arg1 === arg2 ? options.fn(this) : options.inverse(this);
  });

  Handlebars.registerHelper("truncate", function (str, len) {
    if (str && str.length > len) {
      return str.substring(0, len) + "...";
    }
    return str || "";
  });
});

function toggleDetails(button) {
  const fullResponse = button.nextElementSibling;
  const isVisible = fullResponse.style.display === "block";
  fullResponse.style.display = isVisible ? "none" : "block";
  button.textContent = isVisible ? "Show More" : "Show Less";
}

function isAuthenticated() {
  return $.get("is-logged-in")
    .then(function (response) {
      if (response != "Logged in") {
        window.location.href = loginRedirectUrl + window.location.href;
      }
    })
    .fail(function (response) {
      if (response && response.status != 200) {
        window.location.href = loginRedirectUrl;
      }
    });
}

function isSubmitAuthorized() {
  return $.get("hasReportingPrivilege").then(function (response) {
    hasReportingPrivilege = response;
    if (!hasReportingPrivilege) {
      $(".submit").remove();
    }
  });
}

function initTabs() {
  $("#tabs").tabs();
}

function range(start, end) {
  return Array.apply(null, new Array(start - end + 1)).map(function (
    ignore,
    index
  ) {
    return start - index;
  });
}

function fiscalYearRange(start, end) {
  return Array.apply(null, new Array(start - end + 1)).map(function (
    ignore,
    index
  ) {
    return start - index - 1 + "-" + (start - index);
  });
}

function renderPrograms() {
  return $.get("html/programs.html").then(function (template) {
    var canSubmitReport = hasReportingPrivilege;
    return getContent("monthly", canSubmitReport).then(function (content) {
      $("#programs").html(renderTemplate(template, content));
    });
  });
}

function renderWeeklyReport() {
  return $.get("html/programs.html").then(function (template) {
    var canSubmitReport = hasReportingPrivilege;
    return getContent("weekly", canSubmitReport).then(function (content) {
      $("#programs-weekly").html(renderTemplate(template, content));
    });
  });
}

function renderYearlyReport() {
  return $.get("html/programs.html").then(function (template) {
    var isYearlyReport = true;
    return getContent("yearly").then(function (content) {
      $("#programs-yearly").html(renderTemplate(template, content));
    });
  });
}

function getContent(reportType, canSubmitReport) {
  return getDHISPrograms().then(function (programs) {
    return {
      weeks: weeks,
      months: months,
      years: range(supportedYears.startYear, supportedYears.endYear),
      programs: programs,
      reportType: reportType,
      canSubmitReport: canSubmitReport,
    };
  });
}

function getDHISPrograms() {
  return $.getJSON(reportConfigUrl).then(function (reportConfigs) {
    var DHISPrograms = [];
    Object.keys(reportConfigs).forEach(function (reportKey) {
      if (reportConfigs[reportKey].DHISProgram) {
        reportConfigs[reportKey].index = DHISPrograms.length;
        DHISPrograms.push(reportConfigs[reportKey]);
      }
    });
    return DHISPrograms;
  });
}

function putStatus(data, index) {
  var statusTemplate = $("#status-template").html();
  var responseTemplate = $("#response-template").html();

  element("status", index).html(
    renderTemplate(statusTemplate, { status: data.status })
  );
  element("status", index).find(".status-response").on("click", function () {

    if (data.results) {
      $(".popup .content").html(
        renderTemplate(responseTemplate, { results: data.results })
      );
    } else {
      $(".popup .content").html(data.exception);
    }
  });
}

function download(index) {
  var year = element("year", index).val();
  var month = element("month", index).val();
  var programName = element("program-name", index).html();
  var isImam = programName.toLowerCase() === NUTRITION_PROGRAM.toLowerCase();
  var isFamily =
    programName.toLowerCase() === FAMILYPLANNING_PROGRAM.toLowerCase();
  var url = downloadUrl
    .replace("NAME", programName)
    .replace("YEAR", year)
    .replace("MONTH", month)
    .replace("IS_IMAM", isImam)
    .replace("IS_FAMILY", isFamily);
  downloadCommon(url);
}

function downloadFiscalYearReport(index) {
  var yearRange = element("fiscal-year", index).val();
  var years = yearRange.split("-");
  var startYear = years[0];
  var startMonth = 4; //Shrawan
  var endYear = years[1];
  var endMonth = 3; //Ashadh
  var programName = element("program-name", index).html();
  var isImam = programName.toLowerCase() === NUTRITION_PROGRAM.toLowerCase();
  var url = fiscalYearReportUrl
    .replace("NAME", programName)
    .replace("START_YEAR", startYear)
    .replace("START_MONTH", startMonth)
    .replace("END_YEAR", endYear)
    .replace("END_MONTH", endMonth)
    .replace("IS_IMAM", isImam);
  downloadCommon(url);
}

function downloadCommon(url) {
  var a = document.createElement("a");
  a.href = url;
  a.target = "_blank";
  a.click();
  return false;
}

function submit(index, reportType, attribute) {
  spinner.show();
  var year = element("year", index).val();
  var month = element("month", index).val();
  var week = element("week", index).val();
  var programName = element("program-name", index).html();
  var comment = element("comment", index).val();

  if (reportType == "weekly") {
    year = week.split("W")[0];
    week = week.split("W")[1];
    month = null;
  } else {
    week = null
  };

  var parameters = {
    year: year,
    month: month,
    week: week,
    name: programName,
    comment: comment
  };

  disableBtn(element("submit", index));

  var submitTo = submitUrl;
  if (attribute == true) {
    submitTo = submitUrlAtr;
  }
  $.get(submitTo, parameters)
    .done(function (data) {

      if (!$.isEmptyObject(data)) {
        putStatus(
          {
            status: "Success",
            results: data,
          },
          index
        );
      }
    })
    .fail(function (result) {
      if (result.status == 403) {
        putStatus(
          {
            status: "Failure",
            exception: "Not Authenticated",
          },
          index
        );
      }
      putStatus(
        {
          status: "Failure",
          exception: result,
        },
        index
      );
    })
    .always(function () {
      enableBtn(element("submit", index));
      spinner.hide();
    });
}

function confirmAndSubmit(index, reportType, attribute) {
  if (
    confirm("This action cannot be reversed. Are you sure, you want to submit?")
  ) {
    submit(index, reportType, attribute);
  }
}

function getStatus(reportType, index) {
  var programName = element("program-name", index).html();
  var year = element("year", index).val();
  var month = element("month", index).val();
  var week = element("week", index).val();

  if (reportType == "weekly") {
    year = week.split("W")[0];
    week = week.split("W")[1];
    month = null;
  } else {
    week = null;
  }

  var parameters = {
    programName: programName,
    month: month,
    year: year,
    week: week
  };
  spinner.show();
  $.get(logUrl, parameters)
    .done(function (data) {
      data = JSON.parse(data);
      if ($.isEmptyObject(data)) {
        element("comment", index).html("");
        element("status", index).html("");
      } else {
        putStatus(
          {
            status: data.status,
            results: [data],
          },
          index
        );
      }
    })
    .fail(function (response) {
      console.log("failure response");
      if (response.status == 403) {
        putStatus(
          {
            status: "Failure",
            exception: "Not Authenticated",
          },
          index
        );
      }
      putStatus(
        {
          status: "Failure",
          exception: response,
        },
        index
      );
    })
    .always(function () {
      spinner.hide();
    });
}

function element(name, index) {
  var id = name + "-" + index;
  return $('[id="' + id + '"]');
}

function enableBtn(btn) {
  return btn.attr("disabled", false).removeClass("btn-disabled");
}

function disableBtn(btn) {
  return btn.attr("disabled", true).addClass("btn-disabled");
}

function getLogStatus() {
  $("#programs-weekly .week-selector").each(function (index) {
    getStatus("weekly", index);
  });
}

function weeksgenerator(weeksCount) {
  const result = [];
  let currentDate = new Date();

  for (let i = 0; i < weeksCount; i++) {
    // Calculate start of the week (Monday) and end of the week (Sunday)
    const startOfWeek = new Date(currentDate);
    startOfWeek.setDate(
      startOfWeek.getDate() -
        (startOfWeek.getDay() === 0 ? 6 : startOfWeek.getDay() - 1)
    );
    const endOfWeek = new Date(startOfWeek);
    endOfWeek.setDate(startOfWeek.getDate() + 6);

    // Get ISO week number
    const weekNumber = getISOWeekNumber(endOfWeek);

    // Format the output
    const weekYear = endOfWeek.getFullYear();
    const weekName = `Week ${weekNumber} - ${startOfWeek
      .toISOString()
      .slice(0, 10)} - ${endOfWeek.toISOString().slice(0, 10)}`;
    const weekIdentifier = `${weekYear}W${weekNumber}`;

    result.push({
      name: weekName,
      number: weekIdentifier,
    });

    // Move to the previous week
    currentDate.setDate(currentDate.getDate() - 7);
  }

  return result;

  function getISOWeekNumber(date) {
    const tempDate = new Date(date);
    tempDate.setHours(0, 0, 0, 0);
    tempDate.setDate(tempDate.getDate() + 3 - ((tempDate.getDay() + 6) % 7));
    const week1 = new Date(tempDate.getFullYear(), 0, 4);
    return (
      1 +
      Math.round(
        ((tempDate.getTime() - week1.getTime()) / 86400000 -
          3 +
          ((week1.getDay() + 6) % 7)) /
          7
      )
    );
  }
};

function renderTemplate(template, argu) {
  var compiledTemplate = Handlebars.compile(template);
  return compiledTemplate(argu);
};
