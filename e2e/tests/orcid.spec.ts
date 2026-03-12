import { test, expect, Page } from '@playwright/test';

const PORTAL = '/submission-portal';
const ACCOUNT = '/submission-account';

// ORCID sandbox test account
const ORCID_USER = '0009-0006-4821-9361';
const ORCID_PASS = 'cichlid0';

// Submission Account test user (for comparison)
const SA_USER = 'oga-test5';
const SA_PASS = 'oga-test5pass';

const KEYCLOAK_LOGIN_SELECTOR = '#username';

// ── Helpers ──

async function ensureLoggedOut(page: Page): Promise<void> {
  await page.context().clearCookies();
}

async function orcidLogin(page: Page): Promise<void> {
  await page.waitForLoadState('domcontentloaded');

  // Dismiss ORCID cookie consent dialog by accepting all cookies
  try {
    await page.locator('#onetrust-accept-btn-handler').click({ timeout: 5_000 });
    await page.waitForTimeout(1_500);
  } catch {
    // OneTrust banner not found, try generic approach
    try {
      await page.getByRole('button', { name: 'Accept All Cookies' }).first().click({ timeout: 3_000, force: true });
      await page.waitForTimeout(1_500);
    } catch {
      // No cookie dialog
    }
  }

  // ORCID sandbox login page
  const emailInput = page.locator('#userId');
  if (await emailInput.isVisible({ timeout: 15_000 })) {
    await emailInput.fill(ORCID_USER);
    await page.fill('#password', ORCID_PASS);
    await page.locator('#signin-button').click();
    await page.waitForLoadState('domcontentloaded');
  }

  // ORCID may show an authorization/consent screen
  const authorizeBtn = page.locator('#authorize-button');
  if (await authorizeBtn.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await authorizeBtn.click();
    await page.waitForLoadState('domcontentloaded');
  }
}

async function keycloakLogin(page: Page, username: string, password: string): Promise<void> {
  await page.waitForLoadState('domcontentloaded');
  const usernameInput = page.locator(KEYCLOAK_LOGIN_SELECTOR);
  if (await usernameInput.isVisible({ timeout: 10_000 })) {
    await usernameInput.fill(username);
    await page.fill('#password', password);
    await page.click('#kc-login');
    await page.waitForLoadState('domcontentloaded');
  }
}

// Keycloak may show "Update Account Information" on first ORCID login
async function handleKeycloakFirstLogin(page: Page): Promise<void> {
  const updateBtn = page.locator('#kc-accept');
  if (await updateBtn.isVisible({ timeout: 5_000 }).catch(() => false)) {
    // Fill required fields if empty
    const emailField = page.locator('#email');
    if (await emailField.isVisible().catch(() => false)) {
      const val = await emailField.inputValue();
      if (!val) {
        await emailField.fill('orcid-test@example.com');
      }
    }
    const firstNameField = page.locator('#firstName');
    if (await firstNameField.isVisible().catch(() => false)) {
      const val = await firstNameField.inputValue();
      if (!val) {
        await firstNameField.fill('ORCID');
      }
    }
    const lastNameField = page.locator('#lastName');
    if (await lastNameField.isVisible().catch(() => false)) {
      const val = await lastNameField.inputValue();
      if (!val) {
        await lastNameField.fill('TestUser');
      }
    }
    await updateBtn.click();
    await page.waitForLoadState('domcontentloaded');
  }
}

// ── submission-portal ORCID tests ──

test.describe('submission-portal: ORCID Login/Logout', () => {

  test('ORCID login reaches dashboard', async ({ page }) => {
    await ensureLoggedOut(page);

    await page.goto(PORTAL + '/');
    await page.waitForLoadState('domcontentloaded');

    // Click ORCID card (second feature card)
    await page.locator('.feature-card').nth(1).click();

    // Handle ORCID sandbox login
    await orcidLogin(page);

    // Handle Keycloak first-login update profile if shown
    await handleKeycloakFirstLogin(page);

    // Should arrive at dashboard
    await page.waitForURL('**/submission-portal/dashboard**', { timeout: 30_000 });
    await expect(page.locator('.page-title h2')).toBeVisible();

    await page.screenshot({ path: 'screenshots/portal-orcid-login.png' });
  });

  test('logout after ORCID login clears session', async ({ page }) => {
    await ensureLoggedOut(page);

    // Login via ORCID
    await page.goto(PORTAL + '/');
    await page.locator('.feature-card').nth(1).click();
    await orcidLogin(page);
    await handleKeycloakFirstLogin(page);
    await page.waitForURL('**/submission-portal/dashboard**', { timeout: 30_000 });

    // Click logout
    await page.locator('.btn-outline').click();
    await page.waitForLoadState('domcontentloaded');
    await page.waitForURL('**/submission-portal/**', { timeout: 30_000 });

    await page.screenshot({ path: 'screenshots/portal-orcid-after-logout.png' });

    // Verify: going to dashboard must require re-authentication
    await page.goto(PORTAL + '/dashboard');
    await page.waitForLoadState('domcontentloaded');

    // Must see Keycloak login form (not dashboard)
    const keycloakVisible = await page.locator(KEYCLOAK_LOGIN_SELECTOR).isVisible({ timeout: 15_000 }).catch(() => false);
    // Or ORCID login page (if kc_idp_hint was somehow preserved)
    const orcidVisible = await page.locator('#userId').isVisible({ timeout: 3_000 }).catch(() => false);

    expect(keycloakVisible || orcidVisible).toBe(true);
    await page.screenshot({ path: 'screenshots/portal-orcid-logout-verified.png' });
  });

  test('ORCID re-login after logout requires ORCID credentials (prompt=login)', async ({ page }) => {
    await ensureLoggedOut(page);

    // Login via ORCID
    await page.goto(PORTAL + '/');
    await page.locator('.feature-card').nth(1).click();
    await orcidLogin(page);
    await handleKeycloakFirstLogin(page);
    await page.waitForURL('**/submission-portal/dashboard**', { timeout: 30_000 });

    // Logout
    await page.locator('.btn-outline').click();
    await page.waitForLoadState('domcontentloaded');
    await page.waitForURL('**/submission-portal/**', { timeout: 30_000 });

    // Click ORCID login again from landing page
    await page.locator('.feature-card').nth(1).click();

    // With prompt=login, ORCID sandbox should show login form
    // (not auto-authenticate with existing session)
    await page.waitForLoadState('domcontentloaded');

    // Wait a moment for any auto-redirects to settle
    await page.waitForTimeout(3_000);

    const finalUrl = page.url();
    const onDashboard = finalUrl.includes('/dashboard');

    if (onDashboard) {
      // prompt=login did not force re-auth - test fails
      await page.screenshot({ path: 'screenshots/portal-orcid-relogin-FAIL-auto-login.png' });
      expect(onDashboard, 'ORCID auto-authenticated without showing login form').toBe(false);
    } else {
      // Good: login form is shown (ORCID or Keycloak)
      const orcidLoginShown = await page.locator('#userId').isVisible({ timeout: 3_000 }).catch(() => false);
      const keycloakLoginShown = await page.locator(KEYCLOAK_LOGIN_SELECTOR).isVisible({ timeout: 3_000 }).catch(() => false);
      await page.screenshot({ path: 'screenshots/portal-orcid-relogin-prompt-shown.png' });
      expect(orcidLoginShown || keycloakLoginShown).toBe(true);
    }
  });
});

// ── submission-account ORCID tests ──

test.describe('submission-account: ORCID Login/Logout', () => {

  test('ORCID login reaches dashboard', async ({ page }) => {
    await ensureLoggedOut(page);

    await page.goto(ACCOUNT + '/');
    await page.waitForLoadState('domcontentloaded');

    // Click ORCID card (second feature card)
    await page.locator('.feature-card').nth(1).click();

    await orcidLogin(page);
    await handleKeycloakFirstLogin(page);

    // Should arrive at dashboard
    await page.waitForURL('**/submission-account/dashboard**', { timeout: 30_000 });
    await expect(page.locator('.page-title h2')).toBeVisible();

    await page.screenshot({ path: 'screenshots/account-orcid-login.png' });
  });

  test('logout after ORCID login clears session', async ({ page }) => {
    await ensureLoggedOut(page);

    await page.goto(ACCOUNT + '/');
    await page.locator('.feature-card').nth(1).click();
    await orcidLogin(page);
    await handleKeycloakFirstLogin(page);
    await page.waitForURL('**/submission-account/dashboard**', { timeout: 30_000 });

    // Click logout
    await page.locator('.btn-outline').click();
    await page.waitForLoadState('domcontentloaded');
    await page.waitForURL('**/submission-account/**', { timeout: 30_000 });

    await page.screenshot({ path: 'screenshots/account-orcid-after-logout.png' });

    // Verify: going to dashboard must require re-authentication
    await page.goto(ACCOUNT + '/dashboard');
    await page.waitForLoadState('domcontentloaded');

    const keycloakVisible = await page.locator(KEYCLOAK_LOGIN_SELECTOR).isVisible({ timeout: 15_000 }).catch(() => false);
    const orcidVisible = await page.locator('#userId').isVisible({ timeout: 3_000 }).catch(() => false);

    expect(keycloakVisible || orcidVisible).toBe(true);
    await page.screenshot({ path: 'screenshots/account-orcid-logout-verified.png' });
  });

  test('ORCID re-login after logout requires ORCID credentials (prompt=login)', async ({ page }) => {
    await ensureLoggedOut(page);

    await page.goto(ACCOUNT + '/');
    await page.locator('.feature-card').nth(1).click();
    await orcidLogin(page);
    await handleKeycloakFirstLogin(page);
    await page.waitForURL('**/submission-account/dashboard**', { timeout: 30_000 });

    // Logout
    await page.locator('.btn-outline').click();
    await page.waitForLoadState('domcontentloaded');
    await page.waitForURL('**/submission-account/**', { timeout: 30_000 });

    // Click ORCID login again
    await page.locator('.feature-card').nth(1).click();
    await page.waitForLoadState('domcontentloaded');
    await page.waitForTimeout(3_000);

    const finalUrl = page.url();
    const onDashboard = finalUrl.includes('/dashboard');

    if (onDashboard) {
      await page.screenshot({ path: 'screenshots/account-orcid-relogin-FAIL-auto-login.png' });
      expect(onDashboard, 'ORCID auto-authenticated without showing login form').toBe(false);
    } else {
      const orcidLoginShown = await page.locator('#userId').isVisible({ timeout: 3_000 }).catch(() => false);
      const keycloakLoginShown = await page.locator(KEYCLOAK_LOGIN_SELECTOR).isVisible({ timeout: 3_000 }).catch(() => false);
      await page.screenshot({ path: 'screenshots/account-orcid-relogin-prompt-shown.png' });
      expect(orcidLoginShown || keycloakLoginShown).toBe(true);
    }
  });
});
