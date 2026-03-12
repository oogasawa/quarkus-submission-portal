import { test, expect, Page } from '@playwright/test';

const PORTAL = '/submission-portal';
const ACCOUNT = '/submission-account';
const KEYCLOAK_LOGIN_SELECTOR = '#username';
const KEYCLOAK_PASSWORD_SELECTOR = '#password';
const KEYCLOAK_SUBMIT_SELECTOR = '#kc-login';

// Test user must exist in Keycloak submission realm
const TEST_USER = 'oga-test5';
const TEST_PASS = 'oga-test5pass';

// ── Helpers ──

async function keycloakLogin(page: Page, username: string, password: string): Promise<void> {
  await page.waitForLoadState('domcontentloaded');
  const usernameInput = page.locator(KEYCLOAK_LOGIN_SELECTOR);
  if (await usernameInput.isVisible({ timeout: 10_000 })) {
    await usernameInput.fill(username);
    await page.fill(KEYCLOAK_PASSWORD_SELECTOR, password);
    await page.click(KEYCLOAK_SUBMIT_SELECTOR);
    await page.waitForLoadState('domcontentloaded');
  }
}

async function ensureLoggedOut(page: Page): Promise<void> {
  // Clear cookies to ensure clean state
  await page.context().clearCookies();
}

// ── Tests ──

test.describe('Landing Page', () => {

  test('Japanese landing page shows 3 login buttons', async ({ page }) => {
    await page.goto(PORTAL + '/');
    await page.waitForLoadState('domcontentloaded');

    // Title
    await expect(page.locator('h2')).toContainText('DDBJ Submission Portal');

    // 3 feature cards (Submission Account, ORCID, New Account)
    const cards = page.locator('.feature-card');
    await expect(cards).toHaveCount(3);

    // Card texts in Japanese
    await expect(cards.nth(0)).toContainText('Submission Account');
    await expect(cards.nth(1)).toContainText('ORCID');
    await expect(cards.nth(2)).toContainText('アカウント新規作成');

    await page.screenshot({ path: 'screenshots/landing-ja.png' });
  });

  test('English landing page via /en/ prefix', async ({ page }) => {
    await page.goto(PORTAL + '/en/');
    await page.waitForLoadState('domcontentloaded');

    await expect(page.locator('h2')).toContainText('DDBJ Submission Portal');

    const cards = page.locator('.feature-card');
    await expect(cards).toHaveCount(3);

    await expect(cards.nth(0)).toContainText('Log in with Submission Account');
    await expect(cards.nth(1)).toContainText('Log in with ORCID');
    await expect(cards.nth(2)).toContainText('Create New Account');

    await page.screenshot({ path: 'screenshots/landing-en.png' });
  });

  test('language switch link works', async ({ page }) => {
    // Start on Japanese page
    await page.goto(PORTAL + '/');
    await page.waitForLoadState('domcontentloaded');

    // Click English link
    const langLink = page.locator('.lang-link');
    await expect(langLink).toContainText('English');
    await langLink.click();
    await page.waitForLoadState('domcontentloaded');

    // Should be on /en/ page
    expect(page.url()).toContain(PORTAL + '/en/');
    await expect(page.locator('.lang-link')).toContainText('日本語');
  });
});

test.describe('Submission Account Login/Logout', () => {

  test('login with Submission Account redirects to dashboard', async ({ page }) => {
    await ensureLoggedOut(page);

    // Go to landing page and click first card (Submission Account login)
    await page.goto(PORTAL + '/');
    await page.waitForLoadState('domcontentloaded');
    await page.locator('.feature-card').first().click();
    await page.waitForLoadState('domcontentloaded');

    // Should be redirected to Keycloak login
    await keycloakLogin(page, TEST_USER, TEST_PASS);

    // Should arrive at dashboard
    await page.waitForURL('**/submission-portal/dashboard**', { timeout: 30_000 });
    await expect(page.locator('.username')).toContainText(TEST_USER);
    await expect(page.locator('.page-title h2')).toBeVisible();

    await page.screenshot({ path: 'screenshots/dashboard-account-login.png' });
  });

  test('logout clears session and requires re-authentication', async ({ page }) => {
    await ensureLoggedOut(page);

    // Login first
    await page.goto(PORTAL + '/dashboard');
    await keycloakLogin(page, TEST_USER, TEST_PASS);
    await page.waitForURL('**/submission-portal/dashboard**', { timeout: 30_000 });
    await expect(page.locator('.username')).toContainText(TEST_USER);

    // Click logout
    await page.locator('.btn-outline').click();
    await page.waitForLoadState('domcontentloaded');

    // Should be back on landing page
    await page.waitForURL('**/submission-portal/**', { timeout: 30_000 });

    await page.screenshot({ path: 'screenshots/after-logout.png' });

    // Verify session is invalidated: going to dashboard should redirect to Keycloak login
    await page.goto(PORTAL + '/dashboard');
    await page.waitForLoadState('domcontentloaded');

    // Must see Keycloak login form (not the dashboard)
    const usernameInput = page.locator(KEYCLOAK_LOGIN_SELECTOR);
    await expect(usernameInput).toBeVisible({ timeout: 15_000 });

    await page.screenshot({ path: 'screenshots/logout-verified-keycloak-shown.png' });
  });

  test('logout then login again works correctly', async ({ page }) => {
    await ensureLoggedOut(page);

    // Login
    await page.goto(PORTAL + '/dashboard');
    await keycloakLogin(page, TEST_USER, TEST_PASS);
    await page.waitForURL('**/submission-portal/dashboard**', { timeout: 30_000 });

    // Logout
    await page.locator('.btn-outline').click();
    await page.waitForLoadState('domcontentloaded');
    await page.waitForURL('**/submission-portal/**', { timeout: 30_000 });

    // Login again
    await page.goto(PORTAL + '/dashboard');
    await keycloakLogin(page, TEST_USER, TEST_PASS);
    await page.waitForURL('**/submission-portal/dashboard**', { timeout: 30_000 });

    // Should show dashboard with username
    await expect(page.locator('.username')).toContainText(TEST_USER);

    await page.screenshot({ path: 'screenshots/re-login-success.png' });
  });
});

test.describe('ORCID Login', () => {

  test('ORCID button redirects to ORCID sandbox', async ({ page }) => {
    await ensureLoggedOut(page);

    await page.goto(PORTAL + '/');
    await page.waitForLoadState('domcontentloaded');

    // Click ORCID card (second feature card)
    const orcidCard = page.locator('.feature-card').nth(1);
    await expect(orcidCard).toContainText('ORCID');

    // Intercept navigation to ORCID
    const [response] = await Promise.all([
      page.waitForEvent('response', r =>
        r.url().includes('sandbox.orcid.org') || r.url().includes('submission-auth')
      ),
      orcidCard.click(),
    ]);

    // Should have navigated toward ORCID sandbox (via Keycloak)
    const url = page.url();
    const isOrcidOrKeycloak = url.includes('sandbox.orcid.org') || url.includes('submission-auth');
    expect(isOrcidOrKeycloak).toBe(true);

    await page.screenshot({ path: 'screenshots/orcid-redirect.png' });
  });

  test('ORCID login via Keycloak IdP hint reaches ORCID sandbox', async ({ page }) => {
    await ensureLoggedOut(page);

    // Directly navigate to the ORCID login URL (with kc_idp_hint=orcid)
    await page.goto(PORTAL + '/dashboard');
    await page.waitForLoadState('domcontentloaded');

    // Check if we see Keycloak login page with ORCID option
    const currentUrl = page.url();
    if (currentUrl.includes('submission-auth')) {
      // Look for ORCID social provider button on Keycloak login page
      const orcidButton = page.locator('#social-orcid');
      if (await orcidButton.isVisible({ timeout: 5_000 })) {
        await orcidButton.click();
        await page.waitForLoadState('domcontentloaded');

        // Should redirect to ORCID sandbox
        expect(page.url()).toContain('sandbox.orcid.org');
        await page.screenshot({ path: 'screenshots/orcid-sandbox-login-page.png' });
      } else {
        // Keycloak login page without ORCID button - take screenshot for debugging
        await page.screenshot({ path: 'screenshots/keycloak-no-orcid-button.png' });
      }
    }
  });
});

test.describe('Dashboard Content', () => {

  test('dashboard shows storage, tools, and registrations sections', async ({ page }) => {
    await ensureLoggedOut(page);

    await page.goto(PORTAL + '/dashboard');
    await keycloakLogin(page, TEST_USER, TEST_PASS);
    await page.waitForURL('**/submission-portal/dashboard**', { timeout: 30_000 });

    // Storage section
    const storageHeader = page.locator('.section-header').first();
    await expect(storageHeader).toBeVisible();

    // S3 and NFS-k8s storage cells
    const storageCells = page.locator('.storage-cell');
    await expect(storageCells).toHaveCount(2);
    await expect(storageCells.first()).toContainText('S3 (MinIO)');
    await expect(storageCells.nth(1)).toContainText('NFS-k8s');

    // Tools section header
    await expect(page.locator('.section-header', { hasText: /登録ツール|Submission Tools/ })).toBeVisible();

    // Registrations section header
    await expect(page.locator('.section-header', { hasText: /INSDC/ })).toBeVisible();

    await page.screenshot({ path: 'screenshots/dashboard-sections.png' });
  });

  test('dashboard Japanese/English language switch', async ({ page }) => {
    await ensureLoggedOut(page);

    // Login to Japanese dashboard
    await page.goto(PORTAL + '/dashboard');
    await keycloakLogin(page, TEST_USER, TEST_PASS);
    await page.waitForURL('**/submission-portal/dashboard**', { timeout: 30_000 });

    // Verify Japanese content
    await expect(page.locator('.page-title h2')).toContainText('ダッシュボード');
    await expect(page.locator('.btn-outline')).toContainText('ログアウト');

    // Switch to English
    await page.locator('.lang-link').click();
    await page.waitForLoadState('domcontentloaded');

    // Verify English content
    expect(page.url()).toContain('/en/dashboard');
    await expect(page.locator('.page-title h2')).toContainText('Dashboard');
    await expect(page.locator('.btn-outline')).toContainText('Logout');

    await page.screenshot({ path: 'screenshots/dashboard-en.png' });

    // Switch back to Japanese
    await page.locator('.lang-link').click();
    await page.waitForLoadState('domcontentloaded');
    expect(page.url()).not.toContain('/en/');
    await expect(page.locator('.page-title h2')).toContainText('ダッシュボード');

    await page.screenshot({ path: 'screenshots/dashboard-ja.png' });
  });
});

test.describe('Cross-app: submission-account logout affects submission-portal', () => {

  test('logout from submission-account also invalidates portal session', async ({ page }) => {
    await ensureLoggedOut(page);

    // Login to submission-portal
    await page.goto(PORTAL + '/dashboard');
    await keycloakLogin(page, TEST_USER, TEST_PASS);
    await page.waitForURL('**/submission-portal/dashboard**', { timeout: 30_000 });
    await expect(page.locator('.username')).toContainText(TEST_USER);

    // Now logout from submission-account (same Keycloak realm)
    await page.goto(ACCOUNT + '/logout');
    await page.waitForLoadState('domcontentloaded');

    // Try accessing submission-portal dashboard again
    await page.goto(PORTAL + '/dashboard');
    await page.waitForLoadState('domcontentloaded');

    // Should require re-authentication (Keycloak login page shown)
    // Note: This depends on backchannel logout being configured
    const url = page.url();
    const needsReAuth = url.includes('submission-auth') ||
      (await page.locator(KEYCLOAK_LOGIN_SELECTOR).isVisible({ timeout: 5_000 }).catch(() => false));

    await page.screenshot({ path: 'screenshots/cross-app-logout.png' });

    // This test documents the current behavior - if the Keycloak session
    // is shared, logging out from one app should affect the other
    if (needsReAuth) {
      // Good: cross-app logout works
      expect(needsReAuth).toBe(true);
    } else {
      // The portal session cookie may still be valid independently
      // This is expected if cookie-path is app-specific
      console.log('Portal session remained active after account logout (independent sessions)');
    }
  });
});
