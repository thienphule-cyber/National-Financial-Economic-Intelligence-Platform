/**
 * National Financial & Economic Intelligence Platform - dashboard.
 * Plain JavaScript (no build step). Chart.js is loaded from index.html.
 * All data comes from the backend REST API under /api.
 */
"use strict";

(() => {
  // ===================================================================
  // Configuration
  // ===================================================================

  const API = "/api";
  const HISTORY_YEARS = 25;
  const PALETTE = ["#2563eb", "#16a34a", "#f59e0b", "#dc2626", "#7c3aed", "#0891b2", "#db2777", "#65a30d"];

  const DEFAULT_FORECAST_INDICATOR = {
    gdp: "GDP",
    industry: "MANUFACTURING_PCT_GDP",
    investment: "FDI_NET_INFLOWS",
  };

  // Sectors used by the simulation (keys must match the backend enum).
  const SECTORS = [
    ["AGRICULTURE", "Agriculture"],
    ["MANUFACTURING", "Manufacturing"],
    ["MINING", "Mining"],
    ["OTHER_INDUSTRY", "Construction, energy & utilities"],
    ["SERVICES", "Services (technology, tourism, finance)"],
  ];
  const SECTOR_LABEL = Object.fromEntries(SECTORS);

  const SHORT_NAME = {
    AGRICULTURE: "Agriculture",
    INDUSTRY: "Industry",
    MANUFACTURING: "Manufacturing",
    MINING: "Mining (rents)",
    OTHER_INDUSTRY: "Other industry",
    SERVICES: "Services",
    INVESTMENT_PCT_GDP: "Gross capital formation",
    GOV_EXPENDITURE_PCT_GDP: "Government consumption",
    FDI_PCT_GDP: "FDI net inflows",
    GDP_GROWTH: "GDP growth",
    INFLATION: "Inflation",
    UNEMPLOYMENT: "Unemployment",
  };

  const BROAD_SECTORS = ["AGRICULTURE", "INDUSTRY", "SERVICES"];

  const OVERVIEW_KPIS = [
    "GDP", "GDP_GROWTH", "GDP_PER_CAPITA", "INFLATION", "UNEMPLOYMENT",
    "DEBT_PCT_GDP", "FDI_NET_INFLOWS", "EXPORTS", "IMPORTS",
  ];
  const CAPITAL_KPIS = [
    "INVESTMENT_PCT_GDP", "GOV_EXPENDITURE_PCT_GDP", "FDI_NET_INFLOWS", "FDI_PCT_GDP", "GDP_GROWTH", "DEBT_PCT_GDP",
  ];

  // Illustrative sample data for the "own data" panels.
  const SAMPLE_INDUSTRIES = [
    { industry: "Manufacturing", revenue: 190, operatingExpenditure: 167, capitalInvestment: 38, employment: 12000, gdpContributionPct: 24.0, exportContributionPct: 82 },
    { industry: "Agriculture", revenue: 90, operatingExpenditure: 76.5, capitalInvestment: 6, employment: 13900, gdpContributionPct: 11.9, exportContributionPct: 8 },
    { industry: "Technology", revenue: 35, operatingExpenditure: 27, capitalInvestment: 9, employment: 900, gdpContributionPct: 4.5, exportContributionPct: 3 },
    { industry: "Tourism", revenue: 30, operatingExpenditure: 26, capitalInvestment: 8, employment: 3500, gdpContributionPct: 4.0, exportContributionPct: 5 },
  ];
  const SAMPLE_CAPITAL = [
    { industry: "Manufacturing", capitalInvestment: 38, publicSharePct: 10, foreignSharePct: 55, revenue: 190, operatingExpenditure: 167 },
    { industry: "Agriculture", capitalInvestment: 6, publicSharePct: 25, foreignSharePct: 8, revenue: 90, operatingExpenditure: 76.5 },
    { industry: "Energy", capitalInvestment: 12, publicSharePct: 45, foreignSharePct: 12, revenue: 28, operatingExpenditure: 23 },
    { industry: "Technology", capitalInvestment: 9, publicSharePct: 15, foreignSharePct: 45, revenue: 35, operatingExpenditure: 27 },
  ];

  const state = {
    country: "VNM",
    active: "overview",
    indicators: [],
  };

  // ===================================================================
  // DOM helpers
  // ===================================================================

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

  /** Creates an element. Text children are inserted as text (never as HTML). */
  function h(tag, props, ...children) {
    const element = document.createElement(tag);
    for (const [key, value] of Object.entries(props || {})) {
      if (value === null || value === undefined || value === false) continue;
      if (key === "class") element.className = value;
      else if (key.startsWith("on") && typeof value === "function") element.addEventListener(key.slice(2).toLowerCase(), value);
      else element.setAttribute(key, value === true ? "" : value);
    }
    for (const child of children.flat()) {
      if (child === null || child === undefined || child === false) continue;
      element.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return element;
  }

  function fill(container, nodes, emptyText) {
    container.replaceChildren();
    if (nodes.length === 0 && emptyText) container.append(h("p", { class: "muted" }, emptyText));
    else container.append(...nodes);
  }

  function notice(kind, text) {
    return h("div", { class: `notice notice-${kind}` }, text);
  }

  // ===================================================================
  // Formatting
  // ===================================================================

  const compactFmt = new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 2 });
  const plainFmt = new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 });

  const isNum = (value) => typeof value === "number" && Number.isFinite(value);

  function formatValue(value, unit, short = false) {
    if (!isNum(value)) return "-";
    switch (unit) {
      case "USD": return (value < 0 ? "-$" : "$") + compactFmt.format(Math.abs(value));
      case "billion USD": return (value < 0 ? "-$" : "$") + plainFmt.format(Math.abs(value)) + "B";
      case "%": return plainFmt.format(value) + "%";
      case "% of GDP": return plainFmt.format(value) + (short ? "%" : "% of GDP");
      default: return unit ? `${plainFmt.format(value)} ${unit}` : plainFmt.format(value);
    }
  }

  const usd = (value) => formatValue(value, "USD");
  const pct = (value) => formatValue(value, "%");
  const num = (value) => (isNum(value) ? plainFmt.format(value) : "-");
  const signed = (value, suffix = "") => (isNum(value) ? (value > 0 ? "+" : "") + plainFmt.format(value) + suffix : "-");
  const tone = (value) => (isNum(value) && value > 0 ? "pos" : isNum(value) && value < 0 ? "neg" : null);

  function unitOf(key) {
    const found = state.indicators.find((item) => item.key === key);
    return found ? found.unit : "";
  }

  // ===================================================================
  // HTTP
  // ===================================================================

  async function request(path, options = {}) {
    let response;
    try {
      response = await fetch(API + path, options);
    } catch (error) {
      throw new Error("Cannot reach the backend. Make sure it is running (mvn spring-boot:run) "
        + "and open this page from http://localhost:8080.");
    }
    const text = await response.text();
    let data = null;
    if (text) {
      try { data = JSON.parse(text); } catch (error) { data = null; }
    }
    if (!response.ok) {
      throw new Error(data && data.error ? data.error : `Request failed (HTTP ${response.status})`);
    }
    return { data, saved: response.headers.get("X-Saved-To") };
  }

  const api = async (path, options) => (await request(path, options)).data;

  const postJson = (path, body) => request(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  });

  async function fetchSeries(country, key, fromYear) {
    const query = fromYear ? `?fromYear=${fromYear}` : "";
    return api(`/data/${country}/${encodeURIComponent(key)}${query}`);
  }

  /** Returns null instead of throwing, so one missing series does not break a whole view. */
  async function optionalSeries(country, key, fromYear) {
    try { return await fetchSeries(country, key, fromYear); } catch (error) { return null; }
  }

  const pointsOf = (series) => (series ? series.points.map((p) => ({ year: p.year, value: p.value })) : []);

  // ===================================================================
  // Charts
  // ===================================================================

  const charts = {};

  function themeCharts() {
    const css = getComputedStyle(document.documentElement);
    Chart.defaults.color = css.getPropertyValue("--muted").trim();
    Chart.defaults.borderColor = css.getPropertyValue("--border").trim();
    Chart.defaults.font.family = css.getPropertyValue("--font").trim();
  }

  function setChartEmpty(id, empty) {
    const canvas = document.getElementById(id);
    const box = canvas.parentElement;
    let message = box.querySelector(".chart-empty");
    if (empty) {
      if (charts[id]) { charts[id].destroy(); delete charts[id]; }
      canvas.hidden = true;
      if (!message) {
        message = h("div", { class: "chart-empty" }, "No data available");
        box.append(message);
      }
      message.hidden = false;
    } else {
      canvas.hidden = false;
      if (message) message.hidden = true;
    }
  }

  function drawChart(id, config) {
    setChartEmpty(id, false);
    if (charts[id]) charts[id].destroy();
    charts[id] = new Chart(document.getElementById(id), config);
  }

  function chartOptions(unit) {
    return {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: "index", intersect: false },
      plugins: {
        legend: { position: "bottom" },
        tooltip: { callbacks: { label: (c) => `${c.dataset.label}: ${formatValue(c.parsed.y, unit)}` } },
      },
      scales: { y: { ticks: { callback: (value) => formatValue(value, unit, true) } } },
    };
  }

  /** seriesList: [{ label, points: [{ year, value }] }] */
  function lineChart(canvasId, seriesList, unit) {
    const years = [...new Set(seriesList.flatMap((s) => s.points.map((p) => p.year)))].sort((a, b) => a - b);
    if (years.length === 0) {
      setChartEmpty(canvasId, true);
      return;
    }
    const datasets = seriesList.map((series, index) => {
      const byYear = new Map(series.points.map((p) => [p.year, p.value]));
      const color = PALETTE[index % PALETTE.length];
      return {
        label: series.label,
        data: years.map((year) => (byYear.has(year) ? byYear.get(year) : null)),
        borderColor: color,
        backgroundColor: color,
        borderWidth: 2,
        pointRadius: years.length > 40 ? 0 : 2,
        tension: 0.2,
        spanGaps: true,
      };
    });
    drawChart(canvasId, { type: "line", data: { labels: years, datasets }, options: chartOptions(unit) });
  }

  // ===================================================================
  // Reusable UI pieces
  // ===================================================================

  function table(columns, rows) {
    const head = h("thead", null, h("tr", null, columns.map((c) => h("th", { class: c.num ? "num" : null }, c.title))));
    const body = h("tbody", null, rows.map((row) =>
      h("tr", null, row.map((cell, i) => h("td", { class: columns[i].num ? "num" : null }, cell)))));
    return h("table", null, head, body);
  }

  function kpi(label, value, sub, extra, toneClass) {
    return h("div", { class: "kpi" },
      h("div", { class: "kpi-label" }, label),
      h("div", { class: toneClass ? `kpi-value ${toneClass}` : "kpi-value" }, value),
      sub ? h("div", { class: "kpi-sub" }, sub) : null,
      extra || null);
  }

  function trendBadge(trend) {
    if (!trend) return "-";
    const arrow = { rising: "\u2191", falling: "\u2193", stable: "\u2192" }[trend] || "";
    return h("span", { class: `badge badge-${trend}` }, `${arrow} ${trend}`);
  }

  function dataTypeBadge(type) {
    return h("span", { class: `badge badge-${type}` }, type);
  }

  function metricKpi(metric) {
    const parts = [];
    if (metric.latestYear) parts.push(String(metric.latestYear));
    if (isNum(metric.average5y)) parts.push("5y avg " + formatValue(metric.average5y, metric.unit));
    return kpi(metric.label, formatValue(metric.latestValue, metric.unit), parts.join(" \u00b7 "),
      metric.trend ? trendBadge(metric.trend) : null);
  }

  function metricsTable(metrics) {
    return table(
      [{ title: "Indicator" }, { title: "Latest year", num: true }, { title: "Latest value", num: true },
        { title: "5-year average", num: true }, { title: "Trend" }],
      metrics.map((m) => [m.label, m.latestYear ?? "-", formatValue(m.latestValue, m.unit),
        formatValue(m.average5y, m.unit), trendBadge(m.trend)]));
  }

  function renderNotes(container, report) {
    const nodes = [];
    if (report.note) nodes.push(notice("info", report.note));
    if (report.unavailable && report.unavailable.length > 0) {
      nodes.push(notice("warn", "No data returned for: " + report.unavailable.join(", ") + "."));
    }
    container.replaceChildren(...nodes);
  }

  function metricMap(report) {
    return new Map([...report.metrics, ...(report.derived || [])].map((m) => [m.key, m]));
  }

  function showGlobalError(message) {
    const box = $("#global-error");
    box.textContent = message;
    box.hidden = false;
  }

  function hideGlobalError() {
    $("#global-error").hidden = true;
  }

  // ===================================================================
  // View loading framework
  // ===================================================================

  const views = {
    overview: { load: loadOverview, byCountry: true },
    industry: { load: loadIndustry, byCountry: true },
    capital: { load: loadCapital, byCountry: true },
    comparison: { load: loadComparison, byCountry: false },
    forecast: { load: loadForecast, byCountry: true },
    simulation: { load: loadSimulationBaseline, byCountry: true },
  };
  for (const view of Object.values(views)) {
    view.token = 0;
    view.stale = true;
  }

  const showResults = (name, visible) => { $(`#results-${name}`).hidden = !visible; };

  function setStatus(name, kind, message) {
    const element = $(`#status-${name}`);
    element.replaceChildren();
    if (kind === "loading") {
      element.append(h("div", { class: "notice notice-info" }, h("span", { class: "spinner" }),
        "Loading data... The first request downloads data from World Bank / IMF and can take several seconds; "
        + "later requests use the local cache."));
    } else if (kind === "error") {
      element.append(notice("error", message));
    }
  }

  /** Runs a view loader. A newer run (or a country change) cancels the older one. */
  async function runLoad(name, task) {
    const view = views[name];
    const token = ++view.token;
    const isCurrent = () => token === view.token;
    showResults(name, false);
    setStatus(name, "loading");
    try {
      await task(isCurrent);
      if (isCurrent()) {
        view.stale = false;
        setStatus(name, "idle");
      }
    } catch (error) {
      if (isCurrent()) setStatus(name, "error", error.message);
    }
  }

  /** Runs a one-off action (custom analysis, simulation) with its own status box. */
  async function runAction(statusElement, task) {
    statusElement.replaceChildren(h("div", { class: "notice notice-info" }, h("span", { class: "spinner" }), "Working..."));
    try {
      await task();
      statusElement.replaceChildren();
    } catch (error) {
      statusElement.replaceChildren(notice("error", error.message));
    }
  }

  function showView(name) {
    state.active = name;
    for (const section of $$(".view")) section.hidden = section.dataset.view !== name;
    for (const button of $$(".nav-item")) {
      const active = button.dataset.view === name;
      button.classList.toggle("active", active);
      button.setAttribute("aria-current", active ? "page" : "false");
    }
    history.replaceState(null, "", "#" + name);
    if (views[name].stale) runLoad(name, views[name].load);
  }

  function applyCountry() {
    const value = $("#country-input").value.trim().toUpperCase();
    if (!/^[A-Z]{3}$/.test(value)) {
      showGlobalError("Country must be a 3-letter ISO code, e.g. VNM, AUS, USA.");
      return;
    }
    hideGlobalError();
    state.country = value;
    for (const tag of $$("[data-country-tag]")) tag.textContent = value;
    for (const view of Object.values(views)) {
      if (view.byCountry) {
        view.token += 1; // cancels any request still running for the old country
        view.stale = true;
      }
    }
    if (views[state.active].byCountry) runLoad(state.active, views[state.active].load);
  }

  // ===================================================================
  // 1. National Overview
  // ===================================================================

  async function loadOverview(isCurrent) {
    const country = state.country;
    const fromYear = new Date().getFullYear() - HISTORY_YEARS;

    // The report goes first so the series below come from the local cache.
    const report = await api(`/research/${country}/overview`);
    if (!isCurrent()) return;
    const [gdp, growth, inflation, unemployment] = await Promise.all(
      ["GDP", "GDP_GROWTH", "INFLATION", "UNEMPLOYMENT"].map((key) => optionalSeries(country, key, fromYear)));
    if (!isCurrent()) return;

    const byKey = metricMap(report);
    fill($("#overview-kpis"),
      OVERVIEW_KPIS.filter((key) => byKey.has(key)).map((key) => metricKpi(byKey.get(key))),
      "No indicators are available for this country.");
    fill($("#overview-derived"), (report.derived || []).map(metricKpi),
      "Not enough data to derive additional indicators.");
    $("#overview-table").replaceChildren(metricsTable(report.metrics));
    renderNotes($("#overview-notes"), report);
    showResults("overview", true);

    lineChart("chart-overview-gdp", [{ label: "GDP", points: pointsOf(gdp) }], "USD");
    lineChart("chart-overview-rates", [
      { label: SHORT_NAME.GDP_GROWTH, points: pointsOf(growth) },
      { label: SHORT_NAME.INFLATION, points: pointsOf(inflation) },
      { label: SHORT_NAME.UNEMPLOYMENT, points: pointsOf(unemployment) },
    ], "%");
  }

  // ===================================================================
  // 2. Industry Analysis
  // ===================================================================

  async function loadIndustry(isCurrent) {
    const report = await api(`/research/${state.country}/industry`);
    if (!isCurrent()) return;

    const sectors = report.sectors || [];
    fill($("#industry-table"), sectors.length === 0 ? [] : [table(
      [{ title: "Sector" }, { title: "Year", num: true }, { title: "% of GDP", num: true },
        { title: "Est. value (US$)", num: true }, { title: "Employment %", num: true },
        { title: "Relative productivity", num: true }],
      sectors.map((s) => [s.label, s.year ?? "-", pct(s.pctOfGdp), usd(s.estimatedValueUsd),
        pct(s.employmentPct), num(s.relativeProductivity)]))],
    "No sector data is available for this country.");

    fill($("#industry-derived"), (report.derived || []).map(metricKpi),
      "No additional indicators are available.");
    renderNotes($("#industry-notes"), report);
    showResults("industry", true);

    const broad = BROAD_SECTORS
      .map((key) => sectors.find((s) => s.sector === key))
      .filter((s) => s && isNum(s.pctOfGdp));

    if (broad.length === 0) {
      setChartEmpty("chart-industry-share", true);
      setChartEmpty("chart-industry-jobs", true);
      return;
    }

    const labels = broad.map((s) => SHORT_NAME[s.sector]);
    drawChart("chart-industry-share", {
      type: "doughnut",
      data: { labels, datasets: [{ data: broad.map((s) => s.pctOfGdp), backgroundColor: PALETTE.slice(0, 3), borderWidth: 0 }] },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { position: "bottom" },
          tooltip: { callbacks: { label: (c) => `${c.label}: ${plainFmt.format(c.parsed)}% of GDP` } },
        },
      },
    });
    drawChart("chart-industry-jobs", {
      type: "bar",
      data: {
        labels,
        datasets: [
          { label: "Share of GDP (%)", data: broad.map((s) => s.pctOfGdp), backgroundColor: PALETTE[0] },
          { label: "Share of employment (%)", data: broad.map((s) => s.employmentPct ?? null), backgroundColor: PALETTE[2] },
        ],
      },
      options: chartOptions("%"),
    });
  }

  function readJsonArray(selector, what) {
    let parsed;
    try {
      parsed = JSON.parse($(selector).value);
    } catch (error) {
      throw new Error(`The ${what} input is not valid JSON: ${error.message}`);
    }
    if (!Array.isArray(parsed) || parsed.length === 0) {
      throw new Error(`The ${what} input must be a non-empty JSON array.`);
    }
    return parsed;
  }

  async function runCustomIndustry() {
    const body = readJsonArray("#industry-json", "industry data");
    const { data: results } = await postJson("/analysis/industry", body);

    $("#industry-custom-table").replaceChildren(table(
      [{ title: "GDP rank", num: true }, { title: "Industry" }, { title: "Profit", num: true },
        { title: "Profit margin", num: true }, { title: "Revenue per worker", num: true },
        { title: "GDP contribution", num: true }, { title: "Export contribution", num: true }],
      results.map((r) => [r.gdpRank, r.industry, num(r.profit), pct(r.profitMarginPct),
        num(r.productivity), pct(r.gdpContributionPct), pct(r.exportContributionPct)])));
    $("#industry-custom-out").hidden = false;

    drawChart("chart-industry-custom", {
      type: "bar",
      data: {
        labels: results.map((r) => r.industry),
        datasets: [
          { label: "Profit margin (%)", data: results.map((r) => r.profitMarginPct ?? null), backgroundColor: PALETTE[1] },
          { label: "GDP contribution (%)", data: results.map((r) => r.gdpContributionPct), backgroundColor: PALETTE[0] },
          { label: "Export contribution (%)", data: results.map((r) => r.exportContributionPct), backgroundColor: PALETTE[2] },
        ],
      },
      options: chartOptions("%"),
    });
  }

  // ===================================================================
  // 3. Capital Allocation
  // ===================================================================

  async function loadCapital(isCurrent) {
    const country = state.country;
    const fromYear = new Date().getFullYear() - HISTORY_YEARS;

    const report = await api(`/research/${country}/investment`);
    if (!isCurrent()) return;
    const rateKeys = ["INVESTMENT_PCT_GDP", "GOV_EXPENDITURE_PCT_GDP", "FDI_PCT_GDP"];
    const [rateSeries, fdiSeries] = await Promise.all([
      Promise.all(rateKeys.map((key) => optionalSeries(country, key, fromYear))),
      optionalSeries(country, "FDI_NET_INFLOWS", fromYear),
    ]);
    if (!isCurrent()) return;

    const byKey = metricMap(report);
    fill($("#capital-kpis"),
      CAPITAL_KPIS.filter((key) => byKey.has(key)).map((key) => metricKpi(byKey.get(key))),
      "No investment indicators are available for this country.");
    fill($("#capital-derived"), (report.derived || []).map(metricKpi),
      "Not enough data to derive capital indicators.");
    $("#capital-table").replaceChildren(metricsTable(report.metrics));
    renderNotes($("#capital-notes"), report);
    showResults("capital", true);

    lineChart("chart-capital-rates",
      rateKeys.map((key, i) => ({ label: SHORT_NAME[key], points: pointsOf(rateSeries[i]) })), "%");
    lineChart("chart-capital-fdi", [{ label: "FDI net inflows", points: pointsOf(fdiSeries) }], "USD");
  }

  async function runCustomCapital() {
    const body = readJsonArray("#capital-json", "capital data");
    const { data: results } = await postJson("/analysis/capital", body);

    $("#capital-custom-table").replaceChildren(table(
      [{ title: "Efficiency rank", num: true }, { title: "Industry" }, { title: "Capital", num: true },
        { title: "Share of total", num: true }, { title: "Public", num: true }, { title: "Private", num: true },
        { title: "Domestic", num: true }, { title: "Foreign", num: true },
        { title: "Revenue / capital", num: true }, { title: "ROI", num: true }],
      results.map((r) => [r.efficiencyRank, r.industry, num(r.capitalInvestment), pct(r.sharePctOfTotalCapital),
        num(r.publicAmount), num(r.privateAmount), num(r.domesticAmount), num(r.foreignAmount),
        num(r.capitalEfficiency), pct(r.roiPct)])));
    $("#capital-custom-out").hidden = false;

    const labels = results.map((r) => r.industry);
    const stacked = () => {
      const options = chartOptions("");
      options.scales.x = { stacked: true };
      options.scales.y.stacked = true;
      return options;
    };
    drawChart("chart-capital-public", {
      type: "bar",
      data: { labels, datasets: [
        { label: "Public", data: results.map((r) => r.publicAmount), backgroundColor: PALETTE[0] },
        { label: "Private", data: results.map((r) => r.privateAmount), backgroundColor: PALETTE[2] },
      ] },
      options: stacked(),
    });
    drawChart("chart-capital-foreign", {
      type: "bar",
      data: { labels, datasets: [
        { label: "Domestic", data: results.map((r) => r.domesticAmount), backgroundColor: PALETTE[1] },
        { label: "Foreign", data: results.map((r) => r.foreignAmount), backgroundColor: PALETTE[4] },
      ] },
      options: stacked(),
    });
  }

  // ===================================================================
  // 4. Comparison
  // ===================================================================

  async function loadComparison(isCurrent) {
    const codes = $("#cmp-countries").value.trim();
    const indicator = $("#cmp-indicator").value;
    const fromYear = $("#cmp-from").value.trim();
    if (!codes) throw new Error("Enter at least one country code, e.g. VNM,AUS,USA.");
    if (!indicator) throw new Error("Choose an indicator.");

    // One request after the other: both may download the same World Bank data.
    const analysis = await api(`/analysis/countries?codes=${encodeURIComponent(codes)}`);
    if (!isCurrent()) return;
    const query = `countries=${encodeURIComponent(codes)}&indicator=${encodeURIComponent(indicator)}`
      + (fromYear ? `&fromYear=${encodeURIComponent(fromYear)}` : "");
    const compare = await api(`/compare/series?${query}`);
    if (!isCurrent()) return;

    $("#cmp-chart-title").textContent = compare.label + (compare.unit ? ` (${compare.unit})` : "");

    const warnings = (analysis.warnings || []).map((text) => notice("warn", text));
    if (analysis.basis) warnings.unshift(notice("info", analysis.basis));
    $("#cmp-warnings").replaceChildren(...warnings);

    $("#cmp-table").replaceChildren(table(
      [{ title: "Growth rank", num: true }, { title: "Country" }, { title: "GDP growth", num: true },
        { title: "Trade balance", num: true }, { title: "Trade balance / GDP", num: true },
        { title: "FDI / GDP", num: true }, { title: "Investment / GDP", num: true },
        { title: "Debt / GDP", num: true }, { title: "Misery index", num: true }],
      analysis.results.map((r) => [r.growthRank, r.country, pct(r.gdpGrowthPct), usd(r.tradeBalance),
        pct(r.tradeBalancePctGdp), pct(r.fdiPctGdp), pct(r.investmentPctGdp), pct(r.debtPctGdp),
        num(r.miseryIndex)])));
    showResults("comparison", true);

    const series = Object.entries(compare.series).map(([country, points]) => ({
      label: country,
      points: points.map((p) => ({ year: p.year, value: p.value })),
    }));
    lineChart("chart-comparison", series, compare.unit);
  }

  // ===================================================================
  // 5. Forecasting
  // ===================================================================

  function updateForecastHint() {
    const type = $("#forecast-type").value;
    const key = DEFAULT_FORECAST_INDICATOR[type];
    const found = state.indicators.find((item) => item.key === key);
    $("#forecast-hint").textContent = `Default indicator for this type: ${found ? found.label : key}. `
      + "Only historical values are used as model input.";
  }

  async function loadForecast(isCurrent) {
    const country = state.country;
    const type = $("#forecast-type").value;
    const indicator = $("#forecast-indicator").value;
    const model = $("#forecast-model").value;
    const horizon = Number($("#forecast-horizon").value);
    if (!Number.isInteger(horizon) || horizon < 1 || horizon > 20) {
      throw new Error("Years ahead must be a whole number between 1 and 20.");
    }

    const params = new URLSearchParams({ horizon: String(horizon) });
    if (indicator) params.set("indicator", indicator);
    if (model) params.set("model", model);
    if ($("#forecast-save").checked) params.set("save", "true");

    const { data: result, saved } = await request(`/forecast/${type}/${country}?${params}`);
    if (!isCurrent()) return;

    const unit = unitOf(indicator || DEFAULT_FORECAST_INDICATOR[type]);
    const series = result.series;
    const lastObserved = result.lastObservedYear;

    $("#forecast-title").textContent = `${result.subject} (${result.model} model)`;
    fill($("#forecast-saved"), saved ? [notice("info", `Saved to results/${saved}`)] : []);
    fill($("#forecast-kpis"), [
      kpi("Model", result.model),
      kpi("Years of data used", String(result.observations), `last observed year ${lastObserved}`),
      kpi("Years forecasted", String(result.horizon)),
      kpi("Model error (RMSE)", formatValue(result.rmse, unit), "on the historical data"),
    ]);

    const forecasted = series.filter((p) => p.dataType === "forecasted");
    $("#forecast-table").replaceChildren(table(
      [{ title: "Year", num: true }, { title: "Type" }, { title: "Forecast", num: true },
        { title: "Lower (approx. 95%)", num: true }, { title: "Upper (approx. 95%)", num: true }],
      forecasted.map((p) => [p.year, dataTypeBadge(p.dataType), formatValue(p.value, unit),
        formatValue(p.lower, unit), formatValue(p.upper, unit)])));
    fill($("#forecast-notes"), result.note ? [notice("info", result.note)] : []);
    showResults("forecast", true);

    // Chart: historical, estimated (interpolated) and forecasted values, plus an uncertainty band.
    const years = series.map((p) => p.year);
    const at = (p, type2) => (p.dataType === type2 ? p.value : null);
    const link = (p, key) => (p.dataType === "forecasted" ? p[key] ?? null : (p.year === lastObserved ? p.value : null));
    const line = (color) => ({ borderColor: color, backgroundColor: color, borderWidth: 2, tension: 0.2, spanGaps: true });

    drawChart("chart-forecast", {
      type: "line",
      data: {
        labels: years,
        datasets: [
          { label: "Upper bound", data: series.map((p) => link(p, "upper")), borderColor: "transparent",
            backgroundColor: "transparent", pointRadius: 0, fill: false, spanGaps: true },
          { label: "Approx. 95% band", data: series.map((p) => link(p, "lower")), borderColor: "transparent",
            backgroundColor: "rgba(37, 99, 235, 0.15)", pointRadius: 0, fill: "-1", spanGaps: true },
          { label: "Historical", data: series.map((p) => at(p, "historical")), pointRadius: years.length > 40 ? 0 : 2, ...line(PALETTE[0]) },
          { label: "Estimated (interpolated)", data: series.map((p) => at(p, "estimated")), showLine: false,
            pointRadius: 5, pointStyle: "rectRot", borderColor: PALETTE[2], backgroundColor: PALETTE[2] },
          { label: "Forecasted", data: series.map((p) => link(p, "value")), borderDash: [6, 4], pointRadius: 3, ...line(PALETTE[3]) },
        ],
      },
      options: {
        ...chartOptions(unit),
        plugins: {
          legend: { position: "bottom", labels: { filter: (item) => item.text !== "Upper bound" } },
          tooltip: {
            filter: (item) => item.parsed.y !== null && item.dataset.label !== "Upper bound" && item.dataset.label !== "Approx. 95% band",
            callbacks: { label: (c) => `${c.dataset.label}: ${formatValue(c.parsed.y, unit)}` },
          },
        },
      },
    });
  }

  // ===================================================================
  // 6. Simulation
  // ===================================================================

  const sectorName = (key) => SECTOR_LABEL[key] || key;

  async function loadSimulationBaseline(isCurrent) {
    const baseline = await api(`/simulation/baseline/${state.country}`);
    if (!isCurrent()) return;

    $("#sim-output").hidden = true; // an old result belongs to the previous country

    fill($("#sim-baseline-kpis"), [
      kpi("Baseline GDP", usd(baseline.gdpUsd), `year ${baseline.year}`),
      kpi("Investment", usd(baseline.investmentUsd), `${plainFmt.format(baseline.investmentPctGdp)}% of GDP`),
      kpi("Baseline growth", pct(baseline.baselineGrowthPct), "per year (5-year average)"),
      kpi("ICOR", num(baseline.icor), "capital-output ratio"),
    ]);
    $("#sim-baseline-table").replaceChildren(table(
      [{ title: "Sector" }, { title: "Value added (% of GDP)", num: true }, { title: "Value added (US$)", num: true },
        { title: "Employment (%)", num: true }],
      baseline.sectors.map((s) => [sectorName(s.sector), pct(s.valueAddedPct), usd(s.valueUsd), pct(s.employmentPct)])));
    fill($("#sim-baseline-notes"), (baseline.notes || []).map((text) => notice("info", text)));
    showResults("simulation", true);

    $("#sim-growth").placeholder = num(baseline.baselineGrowthPct);
    $("#sim-icor").placeholder = num(baseline.icor);
  }

  function addAdjustmentRow(sector = "MANUFACTURING", change = 10) {
    const select = h("select", { "aria-label": "Sector" },
      SECTORS.map(([key, label]) => h("option", { value: key }, label)));
    select.value = sector;
    const input = h("input", {
      type: "number", step: "0.5", min: "-99", max: "500", value: String(change),
      "aria-label": "Investment change in percent",
    });
    const row = h("div", { class: "adj-row" }, select, input, h("span", { class: "muted" }, "%"),
      h("button", { type: "button", class: "btn btn-ghost", onClick: () => row.remove() }, "Remove"));
    $("#sim-adjustments").append(row);
  }

  function optionalNumber(selector, label) {
    const raw = $(selector).value.trim();
    if (raw === "") return null;
    const value = Number(raw);
    if (!Number.isFinite(value)) throw new Error(`${label} must be a number.`);
    return value;
  }

  function updatePresetHint() {
    const select = $("#sim-preset");
    const option = select.selectedOptions[0];
    $("#sim-preset-hint").textContent = select.value && option ? option.dataset.description || "" : "";
  }

  async function runSimulation() {
    await runAction($("#status-sim-run"), async () => {
      const preset = $("#sim-preset").value;
      const adjustments = $$("#sim-adjustments .adj-row").map((row) => {
        const raw = $("input", row).value.trim();
        const change = Number(raw);
        if (raw === "" || !Number.isFinite(change)) {
          throw new Error("Enter a percentage for every adjustment, or remove the row.");
        }
        return { sector: $("select", row).value, changePct: change };
      });
      if (!preset && adjustments.length === 0) {
        throw new Error("Add at least one investment adjustment, or choose a preset scenario.");
      }

      const body = {
        country: state.country,
        preset: preset || null,
        adjustments,
        fundingMode: $("#sim-funding").value || null,
        horizonYears: optionalNumber("#sim-horizon", "Years to simulate"),
        depreciationPct: optionalNumber("#sim-depreciation", "Capital depreciation"),
        baselineGrowthPct: optionalNumber("#sim-growth", "Baseline growth"),
        icor: optionalNumber("#sim-icor", "ICOR"),
      };

      const endpoint = preset ? "/simulation/scenario" : "/simulation/investment";
      const save = $("#sim-save").checked ? "?save=true" : "";
      const { data: result, saved } = await postJson(endpoint + save, body);
      renderSimulation(result, saved);
    });
  }

  function renderSimulation(result, saved) {
    const s = result.summary;

    fill($("#sim-disclaimer"), [
      notice("warn", result.disclaimer),
      ...(saved ? [notice("info", `Saved to results/${saved}`)] : []),
    ]);
    $("#sim-title").textContent = `${result.scenario} - ${result.country}`;
    $("#sim-subtitle").textContent =
      `Adjustments: ${result.adjustments.map((a) => `${sectorName(a.sector)} ${signed(a.changePct, "%")}`).join("; ")} `
      + `| funding: ${result.fundingMode} | ${result.horizonYears} years from ${result.baselineYear}`;

    fill($("#sim-kpis"), [
      kpi(`GDP impact after ${result.horizonYears} years`, signed(s.gdpImpactPctAtHorizon, "%"),
        `${s.gdpImpactAtHorizon >= 0 ? "+" : ""}${usd(s.gdpImpactAtHorizon)} vs baseline`, null, tone(s.gdpImpactPctAtHorizon)),
      kpi("Employment impact", signed(s.employmentImpactPctAtHorizon, "%"), "weighted across sectors", null,
        tone(s.employmentImpactPctAtHorizon)),
      kpi("Extra investment per year", `${s.extraInvestmentPerYear >= 0 ? "+" : ""}${usd(s.extraInvestmentPerYear)}`,
        `cumulative ${usd(s.cumulativeExtraInvestment)}`, null, tone(s.extraInvestmentPerYear)),
      kpi("Average annual growth", `${pct(s.baselineGrowthPct)} \u2192 ${pct(s.scenarioGrowthPct)}`, "baseline \u2192 scenario"),
      kpi("Output per extra investment", s.outputPerExtraInvestment === undefined ? "-" : num(s.outputPerExtraInvestment),
        "GDP impact / cumulative extra investment"),
      kpi("Investment efficiency index", `${num(s.efficiencyIndexBaseline)} \u2192 ${num(s.efficiencyIndexScenario)}`,
        "weighted by sector efficiency"),
    ]);

    $("#sim-sector-table").replaceChildren(table(
      [{ title: "Sector" }, { title: "Baseline investment", num: true }, { title: "Scenario investment", num: true },
        { title: "Investment change", num: true }, { title: "Output impact", num: true },
        { title: "Employment impact", num: true }],
      result.sectors.map((x) => [sectorName(x.sector), usd(x.baselineInvestment), usd(x.scenarioInvestment),
        signed(x.investmentChangePct, "%"), signed(x.outputImpactPct, "%"), signed(x.employmentImpactPct, "%")])));

    $("#sim-yearly-table").replaceChildren(table(
      [{ title: "Year", num: true }, { title: "Baseline GDP", num: true }, { title: "Scenario GDP", num: true },
        { title: "GDP impact", num: true }, { title: "GDP impact %", num: true }, { title: "Employment impact %", num: true }],
      result.yearly.map((y) => [y.year, usd(y.baselineGdp), usd(y.scenarioGdp), usd(y.gdpImpact),
        signed(y.gdpImpactPct, "%"), signed(y.employmentImpactPct, "%")])));

    const a = result.assumptions;
    const facts = [
      ["Base year", result.baselineYear],
      ["Baseline GDP", usd(a.baselineGdpUsd)],
      ["Total investment", `${usd(a.investmentUsd)} (${plainFmt.format(a.investmentPctGdp)}% of GDP)`],
      ["Baseline growth", `${pct(a.baselineGrowthPct)} per year`],
      ["ICOR", num(a.icor)],
      ["Capital depreciation", `${pct(a.depreciationPct)} per year`],
      ["Funding mode", result.fundingMode],
      ["Horizon", `${result.horizonYears} years`],
    ];
    $("#sim-assumptions").replaceChildren(
      h("dl", { class: "facts" }, facts.flatMap(([key, value]) => [h("dt", null, key), h("dd", null, String(value))])),
      ...(a.baselineNotes || []).map((text) => h("p", { class: "muted small" }, text)),
      h("h4", null, "Sector parameters"),
      h("p", { class: "muted small" },
        "Investment efficiency, employment elasticity and capital intensity are illustrative assumptions, not measured values."),
      table(
        [{ title: "Sector" }, { title: "Value added (% GDP)", num: true }, { title: "Employment (%)", num: true },
          { title: "Investment efficiency", num: true }, { title: "Employment elasticity", num: true },
          { title: "Capital intensity", num: true }],
        a.sectorParameters.map((p) => [sectorName(p.sector), pct(p.valueAddedPct), pct(p.employmentPct),
          num(p.investmentEfficiency), num(p.employmentElasticity), num(p.capitalIntensity)])));

    $("#sim-output").hidden = false;

    lineChart("chart-sim-impact", [
      { label: "GDP impact (%)", points: result.yearly.map((y) => ({ year: y.year, value: y.gdpImpactPct })) },
      { label: "Employment impact (%)", points: result.yearly.map((y) => ({ year: y.year, value: y.employmentImpactPct })) },
    ], "%");

    drawChart("chart-sim-sectors", {
      type: "bar",
      data: {
        labels: result.sectors.map((x) => SHORT_NAME[x.sector] || x.sector),
        datasets: [
          { label: "Output impact (%)", data: result.sectors.map((x) => x.outputImpactPct), backgroundColor: PALETTE[0] },
          { label: "Employment impact (%)", data: result.sectors.map((x) => x.employmentImpactPct), backgroundColor: PALETTE[2] },
        ],
      },
      options: chartOptions("%"),
    });
  }

  // ===================================================================
  // Initialisation
  // ===================================================================

  function fillIndicatorSelect(select, defaultLabel, selected) {
    select.replaceChildren();
    if (defaultLabel) select.append(h("option", { value: "" }, defaultLabel));
    const groups = new Map();
    for (const item of state.indicators) {
      if (!groups.has(item.source)) groups.set(item.source, h("optgroup", { label: item.source }));
      groups.get(item.source).append(h("option", { value: item.key }, item.label));
    }
    select.append(...groups.values());
    if (selected) select.value = selected;
  }

  async function loadModels() {
    try {
      const models = await api("/forecast/models");
      $("#forecast-model").append(...models.map((m) => h("option", { value: m }, m)));
    } catch (error) {
      /* the default option is enough */
    }
  }

  async function loadPresets() {
    try {
      const presets = await api("/simulation/presets");
      $("#sim-preset").append(...presets.map((p) =>
        h("option", { value: p.key, "data-description": p.description }, p.name)));
    } catch (error) {
      /* custom adjustments still work */
    }
  }

  async function init() {
    if (typeof Chart === "undefined") {
      showGlobalError("Chart.js could not be loaded. Check your internet connection (it is loaded from a CDN) and reload the page.");
    } else {
      themeCharts();
      window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", themeCharts);
    }

    $("#cmp-from").value = String(new Date().getFullYear() - 20);
    $("#industry-json").value = JSON.stringify(SAMPLE_INDUSTRIES, null, 2);
    $("#capital-json").value = JSON.stringify(SAMPLE_CAPITAL, null, 2);
    addAdjustmentRow("MANUFACTURING", 10);

    for (const button of $$(".nav-item")) button.addEventListener("click", () => showView(button.dataset.view));
    $("#country-form").addEventListener("submit", (event) => { event.preventDefault(); applyCountry(); });
    $("#cmp-form").addEventListener("submit", (event) => { event.preventDefault(); runLoad("comparison", loadComparison); });
    $("#forecast-form").addEventListener("submit", (event) => { event.preventDefault(); runLoad("forecast", loadForecast); });
    $("#forecast-type").addEventListener("change", updateForecastHint);
    $("#sim-form").addEventListener("submit", (event) => { event.preventDefault(); runSimulation(); });
    $("#sim-add").addEventListener("click", () => addAdjustmentRow("SERVICES", 10));
    $("#sim-preset").addEventListener("change", updatePresetHint);
    $("#industry-run").addEventListener("click", () => runAction($("#industry-custom-status"), runCustomIndustry));
    $("#capital-run").addEventListener("click", () => runAction($("#capital-custom-status"), runCustomCapital));

    try {
      state.indicators = await api("/indicators");
    } catch (error) {
      showGlobalError(error.message);
    }
    fillIndicatorSelect($("#cmp-indicator"), null, "GDP_GROWTH");
    fillIndicatorSelect($("#forecast-indicator"), "Default for this type", "");
    await Promise.allSettled([loadModels(), loadPresets()]);
    updateForecastHint();

    const fromHash = location.hash.slice(1);
    showView(views[fromHash] ? fromHash : "overview");
  }

  init();
})();