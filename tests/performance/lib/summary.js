// handleSummary helper: writes the full k6 summary as JSON to reports/<scenario>-summary.json and prints a compact
// table (p95, p99, error rate, RPS per request name, and every threshold with pass/fail) on stdout.
const OUT_DIR = __ENV.REPORT_DIR || 'reports'

function fmt(ms) {
  if (ms === undefined || ms === null || Number.isNaN(ms)) return '-'
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)} s` : `${ms.toFixed(0)} ms`
}

function pct(rate) {
  return rate === undefined ? '-' : `${(rate * 100).toFixed(2)} %`
}

export function summarize(scenario) {
  return function handleSummary(data) {
    const metrics = data.metrics
    const duration = (data.state && data.state.testRunDurationMs) / 1000 || 1
    const lines = [`\n=== ${scenario}: ${duration.toFixed(0)} s, ${metrics.iterations ? metrics.iterations.values.count : 0} iterations ===`]
    const reqs = metrics.http_reqs ? metrics.http_reqs.values : { count: 0, rate: 0 }
    const failed = metrics.http_req_failed ? metrics.http_req_failed.values.rate : 0
    const checks = metrics.checks ? metrics.checks.values.rate : undefined
    lines.push(`requests ${reqs.count} (${reqs.rate.toFixed(1)}/s) · errors ${pct(failed)} · checks ${pct(checks)}`)
    lines.push('request (tag name)              p95        p99        avg        max        count')
    for (const [key, metric] of Object.entries(metrics)) {
      const match = /^http_req_duration\{name:(.+)\}$/.exec(key)
      if (!match) continue
      const v = metric.values
      lines.push(
        `${match[1].padEnd(30)} ${fmt(v['p(95)']).padEnd(10)} ${fmt(v['p(99)']).padEnd(10)} ${fmt(v.avg).padEnd(10)} ${fmt(v.max).padEnd(10)} ${v.count ?? ''}`,
      )
    }
    for (const [key, metric] of Object.entries(metrics)) {
      if (!metric.type || metric.type !== 'trend' || key.startsWith('http_req') || key.startsWith('iteration') || key.startsWith('group')) continue
      const v = metric.values
      lines.push(`${key.padEnd(30)} ${fmt(v['p(95)']).padEnd(10)} ${fmt(v['p(99)']).padEnd(10)} ${fmt(v.avg).padEnd(10)} ${fmt(v.max).padEnd(10)}`)
    }
    lines.push('thresholds:')
    for (const [key, metric] of Object.entries(metrics)) {
      if (!metric.thresholds) continue
      for (const [expression, result] of Object.entries(metric.thresholds)) {
        lines.push(`  ${result.ok ? 'PASS' : 'FAIL'}  ${key}: ${expression}`)
      }
    }
    return {
      [`${OUT_DIR}/${scenario}-summary.json`]: JSON.stringify(data, null, 2),
      stdout: `${lines.join('\n')}\n`,
    }
  }
}

/** Trend stats every scenario exports (the contract's p95 plus p99 for the report). */
export const TREND_STATS = ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count']
