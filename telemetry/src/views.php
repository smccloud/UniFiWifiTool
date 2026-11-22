<?php
declare(strict_types=1);

function h(?string $value): string
{
    return htmlspecialchars((string) $value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

function format_utc(?string $value): string
{
    if ($value === null || $value === '') {
        return '—';
    }
    $timestamp = strtotime($value . ' UTC');
    return $timestamp === false ? h($value) : gmdate('Y-m-d H:i', $timestamp) . ' UTC';
}

function selected(string $value, string $current): string
{
    return $value === $current ? ' selected' : '';
}

function render_header(string $title): void
{
    ?>
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="theme-color" content="#0b1120">
    <title><?= h($title) ?></title>
    <link rel="stylesheet" href="/assets/style.css">
  </head>
  <body>
    <header class="topbar">
      <a class="brand" href="/">
        <span class="brand-mark">◎</span>
        <span>
          <strong>UniFi WiFi Access Manager</strong>
          <small>Anonymous install stats</small>
        </span>
      </a>
      <nav>
        <a href="/">Public stats</a>
        <a href="/admin">Admin</a>
        <a href="https://github.com/smccloud/UniFiWifiTool">GitHub</a>
      </nav>
    </header>
    <main>
<?php
}

function render_footer(): void
{
    ?>
    </main>
    <footer>
      No IP addresses or network/device identifiers are collected by this service.
    </footer>
  </body>
</html>
<?php
}

function render_public(array $stats): void
{
    render_header('Active installs · UniFi WiFi Access Manager');
    ?>
      <section class="hero">
        <p class="eyebrow">Anonymous telemetry</p>
        <h1>Active installs</h1>
        <p class="lede">
          Aggregate counts from installations that opted in to anonymous install tracking.
          No IP addresses, WiFi names, device names, or controller details are collected.
        </p>
      </section>

      <section class="cards">
        <div class="card"><span>Active last 24 hours</span><strong><?= (int) $stats['counts']['24h'] ?></strong></div>
        <div class="card"><span>Active last 7 days</span><strong><?= (int) $stats['counts']['7d'] ?></strong></div>
        <div class="card"><span>Active last 30 days</span><strong><?= (int) $stats['counts']['30d'] ?></strong></div>
        <div class="card"><span>Known installs</span><strong><?= (int) $stats['counts']['total'] ?></strong></div>
      </section>

      <div class="grid">
        <section class="panel">
          <h2>Versions</h2>
          <p class="muted">Active installs in the last 30 days</p>
          <?php if ($stats['versions']): ?>
          <table>
            <thead><tr><th>Version</th><th class="right">Installs</th></tr></thead>
            <tbody>
              <?php foreach ($stats['versions'] as $row): ?>
              <tr><td><?= h((string) $row['label']) ?></td><td class="right"><?= (int) $row['install_count'] ?></td></tr>
              <?php endforeach; ?>
            </tbody>
          </table>
          <?php else: ?><p class="empty">No data yet.</p><?php endif; ?>
        </section>

        <section class="panel">
          <h2>Platforms</h2>
          <p class="muted">Active installs in the last 30 days</p>
          <?php if ($stats['platforms']): ?>
          <table>
            <thead><tr><th>Platform</th><th class="right">Installs</th></tr></thead>
            <tbody>
              <?php foreach ($stats['platforms'] as $row): ?>
              <tr><td><?= h((string) $row['label']) ?></td><td class="right"><?= (int) $row['install_count'] ?></td></tr>
              <?php endforeach; ?>
            </tbody>
          </table>
          <?php else: ?><p class="empty">No data yet.</p><?php endif; ?>
        </section>

        <section class="panel">
          <h2>Deployments</h2>
          <p class="muted">Active installs in the last 30 days</p>
          <?php if ($stats['deployments']): ?>
          <table>
            <thead><tr><th>Deployment</th><th class="right">Installs</th></tr></thead>
            <tbody>
              <?php foreach ($stats['deployments'] as $row): ?>
              <tr><td><?= h((string) $row['label']) ?></td><td class="right"><?= (int) $row['install_count'] ?></td></tr>
              <?php endforeach; ?>
            </tbody>
          </table>
          <?php else: ?><p class="empty">No data yet.</p><?php endif; ?>
        </section>
      </div>

      <p class="updated">Last updated <?= h(format_utc($stats['updated_at'])) ?>.</p>
<?php
    render_footer();
}

function render_admin(
    array $installs,
    int $total,
    int $page,
    int $pages,
    array $stats,
    string $version,
    string $platform,
    string $active,
    int $retentionDays,
    string $csrfToken,
    ?int $pruned
): void {
    render_header('Admin · Install tracking');
    ?>
      <section class="hero compact">
        <p class="eyebrow">Protected admin section</p>
        <h1>Anonymous installs</h1>
        <p class="lede">
          <?= (int) $total ?> install<?= $total === 1 ? '' : 's' ?> matching the current filter.
          Install IDs are random and are not derived from hardware, IP addresses, or networks.
        </p>
      </section>

      <section class="panel">
        <form class="filters" method="get" action="/admin">
          <label>Version
            <select name="version">
              <option value="">All versions</option>
              <?php foreach ($stats['versions'] as $row): ?>
              <option value="<?= h((string) $row['label']) ?>"<?= selected((string) $row['label'], $version) ?>>
                <?= h((string) $row['label']) ?> (<?= (int) $row['install_count'] ?>)
              </option>
              <?php endforeach; ?>
            </select>
          </label>
          <label>Platform
            <select name="platform">
              <option value="">All platforms</option>
              <?php foreach ($stats['platforms'] as $row): ?>
              <option value="<?= h((string) $row['label']) ?>"<?= selected((string) $row['label'], $platform) ?>>
                <?= h((string) $row['label']) ?> (<?= (int) $row['install_count'] ?>)
              </option>
              <?php endforeach; ?>
            </select>
          </label>
          <label>Activity
            <select name="active">
              <option value="">Any time</option>
              <option value="24h"<?= selected('24h', $active) ?>>Last 24 hours</option>
              <option value="7d"<?= selected('7d', $active) ?>>Last 7 days</option>
              <option value="30d"<?= selected('30d', $active) ?>>Last 30 days</option>
            </select>
          </label>
          <button class="button" type="submit">Filter</button>
          <a class="button ghost" href="/admin">Reset</a>
        </form>
      </section>

      <?php if ($pruned !== null): ?>
      <div class="notice">Pruned <?= (int) $pruned ?> stale install<?= $pruned === 1 ? '' : 's' ?>.</div>
      <?php endif; ?>

      <section class="panel">
        <?php if ($installs): ?>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Install ID</th><th>Version</th><th>Platform</th><th>Deployment</th>
                <th>First seen</th><th>Last seen</th><th>Active</th>
              </tr>
            </thead>
            <tbody>
              <?php foreach ($installs as $install): ?>
              <tr>
                <td class="mono"><?= h((string) $install['install_id']) ?></td>
                <td><?= h((string) $install['app_version']) ?></td>
                <td><?= h((string) $install['platform']) ?></td>
                <td><?= h((string) $install['deployment']) ?></td>
                <td><?= h(format_utc((string) $install['first_seen'])) ?></td>
                <td><?= h(format_utc((string) $install['last_seen'])) ?></td>
                <td>
                  <?php if (install_is_active((string) $install['last_seen'])): ?>
                  <span class="badge good">Active</span>
                  <?php else: ?><span class="badge">Stale</span><?php endif; ?>
                </td>
              </tr>
              <?php endforeach; ?>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <?php
          $params = [];
          if ($version !== '') {
              $params['version'] = $version;
          }
          if ($platform !== '') {
              $params['platform'] = $platform;
          }
          if ($active !== '') {
              $params['active'] = $active;
          }
          if ($page > 1) {
              $params['page'] = $page - 1;
          }
          ?>
          <?php if ($page > 1): ?><a class="button ghost" href="/admin?<?= h(http_build_query($params)) ?>">Previous</a><?php endif; ?>
          <span class="muted">Page <?= (int) $page ?> of <?= (int) $pages ?></span>
          <?php
          if ($page < $pages) {
              $params['page'] = $page + 1;
          }
          ?>
          <?php if ($page < $pages): ?><a class="button ghost" href="/admin?<?= h(http_build_query($params)) ?>">Next</a><?php endif; ?>
        </div>
        <?php else: ?><p class="empty">No installs match the current filter.</p><?php endif; ?>
      </section>

      <section class="panel">
        <h2>Retention</h2>
        <p class="muted">
          Installs that have not sent a heartbeat in <?= (int) $retentionDays ?> days are
          pruned automatically once per day and can also be pruned here. The public page
          only shows aggregate counts; individual install IDs are visible only in this
          authenticated section.
        </p>
        <form method="post" action="/admin/prune">
          <input type="hidden" name="csrf_token" value="<?= h($csrfToken) ?>">
          <button class="button danger" type="submit">Prune stale installs</button>
        </form>
      </section>
<?php
    render_footer();
}
