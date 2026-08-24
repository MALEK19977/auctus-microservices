import {
  Directive, ElementRef, HostListener, Input, NgZone, OnDestroy, Renderer2
} from '@angular/core';

/**
 * Pointer-tracked 3D tilt for bento tiles.
 *
 * The directive only ever writes four custom properties - --rx, --ry, --mx and
 * --my - and lets `.au-tilt` in styles.css decide what to do with them. Keeping
 * the maths here and the presentation there means a tile without JavaScript, or
 * a user who has asked for reduced motion, still gets a perfectly good card that
 * simply does not tilt.
 *
 * All of this runs outside the Angular zone. A pointermove fires dozens of times
 * a second and none of it changes application state, so there is no reason to
 * make the framework re-check the view for any of it.
 */
@Directive({
  selector: '[auTilt]',
  standalone: true
})
export class TiltDirective implements OnDestroy {
  /** Maximum rotation in degrees at the very edge of the tile. */
  @Input() auTiltMax = 9;
  /** How far the tile lifts towards the viewer, in pixels. */
  @Input() auTiltLift = 6;

  private frame = 0;
  private readonly el: HTMLElement;
  /** Honour the OS-level motion preference, and skip the effect on touch. */
  private readonly enabled: boolean;

  constructor(host: ElementRef<HTMLElement>, private r: Renderer2, private zone: NgZone) {
    this.el = host.nativeElement;
    this.el.classList.add('au-tilt');

    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    const coarse = window.matchMedia?.('(pointer: coarse)').matches;
    this.enabled = !reduced && !coarse;
  }

  @HostListener('pointermove', ['$event'])
  onMove(ev: PointerEvent): void {
    if (!this.enabled) { return; }

    this.zone.runOutsideAngular(() => {
      cancelAnimationFrame(this.frame);
      this.frame = requestAnimationFrame(() => {
        const b = this.el.getBoundingClientRect();
        if (!b.width || !b.height) { return; }

        // Pointer position within the tile, normalised to -0.5 … +0.5.
        const px = (ev.clientX - b.left) / b.width - 0.5;
        const py = (ev.clientY - b.top) / b.height - 0.5;

        // Y drives rotation about X. Negated so the edge nearest the pointer
        // dips towards the viewer, which is what reads as "pushing" the tile.
        this.set('--rx', `${(-py * this.auTiltMax).toFixed(2)}deg`);
        this.set('--ry', `${(px * this.auTiltMax).toFixed(2)}deg`);
        this.set('--ty', `${-this.auTiltLift}px`);

        // Feeds the specular highlight in .au-sheen.
        this.set('--mx', `${((px + 0.5) * 100).toFixed(1)}%`);
        this.set('--my', `${((py + 0.5) * 100).toFixed(1)}%`);
      });
    });
  }

  @HostListener('pointerenter')
  onEnter(): void {
    // While the pointer is down on the tile we want it to track immediately
    // rather than easing, so the tile feels attached to the cursor.
    if (this.enabled) { this.el.classList.add('is-live'); }
  }

  @HostListener('pointerleave')
  onLeave(): void {
    cancelAnimationFrame(this.frame);
    // Dropping .is-live restores the 300ms ease, so the tile settles back
    // gracefully instead of snapping flat.
    this.el.classList.remove('is-live');
    this.set('--rx', '0deg');
    this.set('--ry', '0deg');
    this.set('--ty', '0px');
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.frame);
  }

  private set(prop: string, value: string): void {
    this.r.setStyle(this.el, prop, value, 2 /* RendererStyleFlags2.DashCase */);
  }
}
