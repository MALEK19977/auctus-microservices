import {
  Directive, ElementRef, Input, NgZone, OnDestroy, OnInit, Renderer2
} from '@angular/core';

/**
 * Scroll parallax for background layers.
 *
 * Writes a single custom property, --par, which `.au-parallax-layer` turns into
 * a translate3d. A negative speed makes the layer trail the content, which is
 * what gives the dashboard its sense of depth as the agent scrolls the history
 * table.
 *
 * Listens passively and outside the Angular zone: scrolling must never be able
 * to make the framework do work, or long tables start to stutter.
 */
@Directive({
  selector: '[auParallax]',
  standalone: true
})
export class ParallaxDirective implements OnInit, OnDestroy {
  /** Fraction of scroll distance the layer moves. 0.2 = a fifth as fast. */
  @Input() auParallax: number | string = 0.2;

  private frame = 0;
  private onScroll = (): void => this.schedule();
  private enabled = true;

  constructor(
    private host: ElementRef<HTMLElement>,
    private r: Renderer2,
    private zone: NgZone
  ) {}

  ngOnInit(): void {
    this.host.nativeElement.classList.add('au-parallax-layer');

    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      this.enabled = false;
      return;
    }

    this.zone.runOutsideAngular(() => {
      window.addEventListener('scroll', this.onScroll, { passive: true });
      this.schedule();
    });
  }

  private schedule(): void {
    cancelAnimationFrame(this.frame);
    this.frame = requestAnimationFrame(() => {
      const speed = Number(this.auParallax) || 0.2;
      const offset = window.scrollY * speed;
      this.r.setStyle(
        this.host.nativeElement, '--par', `${offset.toFixed(1)}px`, 2
      );
    });
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.frame);
    if (this.enabled) {
      window.removeEventListener('scroll', this.onScroll);
    }
  }
}
