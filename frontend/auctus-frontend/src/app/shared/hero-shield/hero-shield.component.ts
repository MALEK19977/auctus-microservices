import {
  AfterViewInit, Component, ElementRef, NgZone, OnDestroy, ViewChild
} from '@angular/core';

/**
 * The AUCTUS mark, rendered as a real extruded shield in WebGL.
 *
 * `three` is pulled in with a dynamic `import()` inside ngAfterViewInit rather
 * than a static import at the top of the file. That is deliberate: it keeps the
 * library in its own lazy chunk that only the login route ever downloads, so the
 * dashboards - which an agent loads far more often - pay nothing for it. The
 * `typeof import('three')` type is erased at compile time and costs nothing.
 *
 * Everything renders outside the Angular zone. A 60fps render loop inside the
 * zone would ask the framework to re-check the view sixty times a second for a
 * scene that never touches application state.
 */
@Component({
  selector: 'app-hero-shield',
  standalone: true,
  template: `<div class="hero-canvas" #host [attr.aria-hidden]="true"></div>`,
  styles: [`
    :host { display: block; width: 100%; height: 100%; }
    .hero-canvas { width: 100%; height: 100%; }
    .hero-canvas canvas { display: block; width: 100% !important; height: 100% !important; }
  `]
})
export class HeroShieldComponent implements AfterViewInit, OnDestroy {
  @ViewChild('host', { static: true }) hostRef!: ElementRef<HTMLDivElement>;

  private raf = 0;
  private disposed = false;
  private cleanup: Array<() => void> = [];

  /** Pointer target and its damped follower, both in radians. */
  private aim = { x: 0, y: 0 };
  private cur = { x: 0, y: 0 };

  constructor(private zone: NgZone) {}

  async ngAfterViewInit(): Promise<void> {
    // A shield that cannot animate is just a heavy image; skip WebGL entirely
    // for anyone who has asked the OS for reduced motion and let the CSS
    // fallback mark behind it show through.
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) { return; }

    let THREE: typeof import('three');
    try {
      THREE = await import('three');
    } catch {
      return; // Offline or blocked - the CSS mark underneath stands in.
    }
    if (this.disposed) { return; }

    this.zone.runOutsideAngular(() => this.build(THREE));
  }

  private build(THREE: typeof import('three')): void {
    const host = this.hostRef.nativeElement;
    const w = () => host.clientWidth || 1;
    const h = () => host.clientHeight || 1;

    const scene = new THREE.Scene();

    const camera = new THREE.PerspectiveCamera(38, w() / h(), 0.1, 100);
    camera.position.set(0, 0, 6.2);

    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.setSize(w(), h());
    host.appendChild(renderer.domElement);

    const NAVY = 0x2f4156;
    const NAVY_DEEP = 0x1b2836;
    const SKY = 0xc8d9e6;

    // ---- the shield -------------------------------------------------------
    // A classic crest: flat shoulders, straight flanks, and a point at the
    // bottom drawn with two mirrored beziers.
    const outline = new THREE.Shape();
    outline.moveTo(0, 1.30);
    outline.lineTo(1.00, 0.92);
    outline.lineTo(1.00, -0.10);
    outline.bezierCurveTo(1.00, -0.78, 0.56, -1.12, 0, -1.38);
    outline.bezierCurveTo(-0.56, -1.12, -1.00, -0.78, -1.00, -0.10);
    outline.lineTo(-1.00, 0.92);
    outline.closePath();

    const shieldGeo = new THREE.ExtrudeGeometry(outline, {
      depth: 0.34,
      bevelEnabled: true,
      bevelThickness: 0.075,
      bevelSize: 0.065,
      bevelSegments: 4,
      curveSegments: 36
    });
    shieldGeo.center();

    const shieldMat = new THREE.MeshStandardMaterial({
      color: NAVY, metalness: 0.42, roughness: 0.34
    });
    const shield = new THREE.Mesh(shieldGeo, shieldMat);

    // The glowing sky edge the brief asks for, drawn as real geometry edges
    // rather than a post-process - far cheaper and it reads crisply at any size.
    const edgeGeo = new THREE.EdgesGeometry(shieldGeo, 26);
    const edgeMat = new THREE.LineBasicMaterial({
      color: SKY, transparent: true, opacity: 0.85
    });
    const edges = new THREE.LineSegments(edgeGeo, edgeMat);

    // Rim aura: the same silhouette, scaled up and rendered back-face only, so
    // it survives only as a halo around the edge of the solid shield.
    const auraMat = new THREE.MeshBasicMaterial({
      color: SKY, transparent: true, opacity: 0.16,
      side: THREE.BackSide, depthWrite: false
    });
    const aura = new THREE.Mesh(shieldGeo, auraMat);
    aura.scale.setScalar(1.09);

    // ---- the validation tick, sitting proud of the shield face ------------
    const tick = new THREE.Shape();
    tick.moveTo(-0.44, 0.06);
    tick.lineTo(-0.14, -0.26);
    tick.lineTo(0.44, 0.42);
    tick.lineTo(0.32, 0.55);
    tick.lineTo(-0.14, -0.02);
    tick.lineTo(-0.31, 0.18);
    tick.closePath();

    const tickGeo = new THREE.ExtrudeGeometry(tick, {
      depth: 0.10, bevelEnabled: true,
      bevelThickness: 0.02, bevelSize: 0.02, bevelSegments: 2
    });
    tickGeo.center();
    const tickMesh = new THREE.Mesh(
      tickGeo,
      new THREE.MeshStandardMaterial({ color: SKY, metalness: 0.3, roughness: 0.28 })
    );
    tickMesh.position.set(0, 0.02, 0.30);
    tickMesh.scale.setScalar(1.05);

    const crest = new THREE.Group();
    crest.add(shield, edges, aura, tickMesh);
    scene.add(crest);

    // ---- counter-rotating wireframe cage ---------------------------------
    const cageGeo = new THREE.IcosahedronGeometry(2.35, 1);
    const cage = new THREE.LineSegments(
      new THREE.WireframeGeometry(cageGeo),
      new THREE.LineBasicMaterial({ color: SKY, transparent: true, opacity: 0.18 })
    );
    scene.add(cage);

    // ---- drifting motes ---------------------------------------------------
    const COUNT = 90;
    const pos = new Float32Array(COUNT * 3);
    for (let i = 0; i < COUNT; i++) {
      // Rejection-free spherical shell: random direction, random radius in band.
      const th = Math.random() * Math.PI * 2;
      const ph = Math.acos(2 * Math.random() - 1);
      const r = 2.6 + Math.random() * 1.7;
      pos[i * 3] = r * Math.sin(ph) * Math.cos(th);
      pos[i * 3 + 1] = r * Math.sin(ph) * Math.sin(th);
      pos[i * 3 + 2] = r * Math.cos(ph);
    }
    const moteGeo = new THREE.BufferGeometry();
    moteGeo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    const moteMat = new THREE.PointsMaterial({
      color: SKY, size: 0.045, transparent: true, opacity: 0.6, sizeAttenuation: true
    });
    const motes = new THREE.Points(moteGeo, moteMat);
    scene.add(motes);

    // ---- light ------------------------------------------------------------
    const key = new THREE.DirectionalLight(0xffffff, 2.1);
    key.position.set(-3.2, 4.0, 5.0);
    const rim = new THREE.PointLight(SKY, 42, 22);
    rim.position.set(4.2, -1.4, 3.2);
    const fill = new THREE.PointLight(0xffffff, 14, 20);
    fill.position.set(-4.0, -2.6, 2.4);
    scene.add(key, rim, fill, new THREE.AmbientLight(NAVY_DEEP, 2.4));

    // ---- interaction ------------------------------------------------------
    // Tracked on the window so the shield reacts to the whole login page, not
    // just the small box it occupies.
    const onPointer = (e: PointerEvent) => {
      this.aim.x = (e.clientX / window.innerWidth - 0.5) * 0.85;
      this.aim.y = (e.clientY / window.innerHeight - 0.5) * 0.6;
    };
    window.addEventListener('pointermove', onPointer, { passive: true });
    this.cleanup.push(() => window.removeEventListener('pointermove', onPointer));

    const ro = new ResizeObserver(() => {
      camera.aspect = w() / h();
      camera.updateProjectionMatrix();
      renderer.setSize(w(), h());
    });
    ro.observe(host);
    this.cleanup.push(() => ro.disconnect());

    // Stop burning GPU on a tab nobody is looking at.
    let hidden = document.hidden;
    const onVis = () => {
      hidden = document.hidden;
      if (!hidden) { loop(); }
    };
    document.addEventListener('visibilitychange', onVis);
    this.cleanup.push(() => document.removeEventListener('visibilitychange', onVis));

    // ---- render loop ------------------------------------------------------
    const clock = new THREE.Clock();
    const loop = () => {
      if (this.disposed || hidden) { return; }
      this.raf = requestAnimationFrame(loop);

      const t = clock.getElapsedTime();

      // Ease towards the pointer instead of snapping - this is what makes the
      // object feel weighted and under control rather than twitchy.
      this.cur.x += (this.aim.x - this.cur.x) * 0.055;
      this.cur.y += (this.aim.y - this.cur.y) * 0.055;

      crest.rotation.y = t * 0.28 + this.cur.x;
      crest.rotation.x = this.cur.y;
      crest.position.y = Math.sin(t * 0.9) * 0.055;

      cage.rotation.y = -t * 0.11 - this.cur.x * 0.45;
      cage.rotation.x = t * 0.05;

      motes.rotation.y = t * 0.045;
      motes.rotation.z = t * 0.02;

      renderer.render(scene, camera);
    };
    loop();

    // ---- teardown ---------------------------------------------------------
    this.cleanup.push(() => {
      [shieldGeo, edgeGeo, tickGeo, cageGeo, moteGeo].forEach(g => g.dispose());
      [shieldMat, edgeMat, auraMat, moteMat].forEach(m => m.dispose());
      (tickMesh.material as any).dispose?.();
      (cage.material as any).dispose?.();
      (cage.geometry as any).dispose?.();
      renderer.dispose();
      renderer.domElement.remove();
    });
  }

  ngOnDestroy(): void {
    this.disposed = true;
    cancelAnimationFrame(this.raf);
    this.cleanup.forEach(fn => {
      try { fn(); } catch { /* teardown must never throw on route change */ }
    });
    this.cleanup = [];
  }
}
