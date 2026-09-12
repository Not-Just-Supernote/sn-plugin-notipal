import { normalizeInsertionLeft } from '../InsertionGeometry';

describe('normalizeInsertionLeft', () => {
  it('clamps the logged far-right lasso to a usable column', () => {
    const geometry = normalizeInsertionLeft(1404, 1386, 36);

    expect(geometry.left).toBe(842);
    expect(geometry.right - geometry.left).toBeGreaterThanOrEqual(geometry.minTextboxWidth);
    expect(geometry.right - geometry.left).toBeGreaterThanOrEqual(288);
  });

  it('keeps an in-range insertion column unchanged', () => {
    expect(normalizeInsertionLeft(1404, 320, 36).left).toBe(320);
  });

  it('uses the minimum textbox width when it is stricter', () => {
    const geometry = normalizeInsertionLeft(400, 390, 36);

    expect(geometry.left).toBe(96);
    expect(geometry.right - geometry.left).toBeGreaterThanOrEqual(288);
  });

  it('never returns a negative insertion column', () => {
    expect(normalizeInsertionLeft(1404, -50, 36).left).toBe(0);
  });
});
