import { test, expect } from '@playwright/test';

test('real UI creates accounts, funds, transfers and safely replays', async ({ page }) => {
  const suffix = Date.now().toString(); const alice = `UI Alice ${suffix}`, bob = `UI Bob ${suffix}`;
  await page.goto('/');
  for (const name of [alice,bob]) {
    await page.getByRole('button',{name:'New account',exact:true}).click();
    await page.getByLabel('Account name').fill(name);
    await page.getByRole('button',{name:'Create account',exact:true}).click();
    await expect(page.getByLabel('Account name')).toHaveCount(0);
  }
  await page.getByRole('button',{name:'Controlled demo funding'}).click();
  await page.getByLabel('Funding account',{exact:true}).selectOption({label:`${alice} · €0.00`});
  await page.getByLabel('Funding amount (EUR)',{exact:true}).fill('2000.00');
  await page.getByLabel('Demo token').fill(process.env.DEMO_FUNDING_TOKEN!);
  await page.getByRole('button',{name:'Fund demo account',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('COMPLETED');
  await page.getByLabel('Source account',{exact:true}).selectOption({label:`${alice} · €2,000.00`});
  await page.getByLabel('Recipient account',{exact:true}).selectOption({label:`${bob} · €0.00`});
  await page.getByLabel('Amount (EUR)',{exact:true}).fill('10.00');
  await page.getByRole('button',{name:'Submit transfer',exact:true}).click();
  await expect(page.getByRole('status')).toHaveText('COMPLETED');
  await expect(page.locator('.rule')).toContainText('NEW_RECIPIENT');
  await page.getByRole('button',{name:'Retry same request',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('replayed, no new payment');
  await page.getByRole('button',{name:'Accounts & ledger'}).click();
  await page.getByRole('button').filter({hasText:alice}).first().click();
  await expect(page.locator('.history h3')).toContainText('€1,990.00');
  await expect(page.locator('.history-row')).toHaveCount(2);
  await page.setViewportSize({width:390,height:844});
  await expect(page.getByRole('heading',{name:'Accounts & ledger'})).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBeTruthy();
  await page.screenshot({path:'test-results/sentinel-mobile.png',fullPage:true});
});

test('review request stays unexecuted through replay and browser reload', async ({ page, request }) => {
  const name=`Review UI ${Date.now()}`;
  const a=await (await request.post('/api/accounts',{data:{name}})).json();
  const b=await (await request.post('/api/accounts',{data:{name:`${name} recipient`}})).json();
  await request.post('/api/demo/fundings',{data:{targetAccountId:a.id,amountCents:200000},headers:{'X-Demo-Token':process.env.DEMO_FUNDING_TOKEN!,'Idempotency-Key':crypto.randomUUID()}});
  await page.goto('/');
  await page.getByLabel('Source account',{exact:true}).selectOption(a.id);
  await page.getByLabel('Recipient account',{exact:true}).selectOption(b.id);
  await page.getByLabel('Amount (EUR)',{exact:true}).fill('1000.00');
  await page.getByRole('button',{name:'Submit transfer',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('REVIEW');
  await expect(page.locator('.gauge')).toContainText('55');
  await page.reload();
  await page.getByRole('button',{name:'Retry same request',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('REVIEW · replayed');
  const source=await (await request.get(`/api/accounts/${a.id}`)).json();
  const target=await (await request.get(`/api/accounts/${b.id}`)).json();
  expect(source.balanceCents).toBe(200000); expect(target.balanceCents).toBe(0);
  const history=await (await request.get(`/api/accounts/${b.id}/transactions`)).json();
  expect(history.items).toHaveLength(0);
  await expect(page.locator('.audit li')).toHaveCount(2);
  await page.evaluate(()=>window.scrollTo(0,0));
  await page.screenshot({path:'test-results/sentinel-console.png',fullPage:true});
});
