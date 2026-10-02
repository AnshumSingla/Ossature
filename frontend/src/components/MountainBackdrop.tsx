// Decorative background for the auth pages. Pure inline SVG, no external assets.
export function MountainBackdrop() {
  return (
    <svg
      className="mountain-backdrop"
      viewBox="0 0 1440 900"
      preserveAspectRatio="xMidYMid slice"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#d8dcef" />
          <stop offset="45%" stopColor="#cdd3e8" />
          <stop offset="100%" stopColor="#9b9fdc" />
        </linearGradient>
        <linearGradient id="ridge1" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#aeb0e6" />
          <stop offset="100%" stopColor="#9496d6" />
        </linearGradient>
        <linearGradient id="ridge2" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#8f8fd6" />
          <stop offset="100%" stopColor="#7072c2" />
        </linearGradient>
        <linearGradient id="ridge3" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#6b6cbd" />
          <stop offset="100%" stopColor="#53549f" />
        </linearGradient>
        <linearGradient id="ridge4" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#4a4b8f" />
          <stop offset="100%" stopColor="#36356b" />
        </linearGradient>
      </defs>

      <rect width="1440" height="900" fill="url(#sky)" />

      {/* soft clouds */}
      <g fill="#fff" opacity="0.35">
        <ellipse cx="310" cy="150" rx="90" ry="22" />
        <ellipse cx="380" cy="140" rx="60" ry="18" />
        <ellipse cx="980" cy="110" rx="100" ry="20" />
        <ellipse cx="1060" cy="120" rx="55" ry="15" />
      </g>

      {/* birds */}
      <g stroke="#3f3f73" strokeWidth="4" strokeLinecap="round" fill="none" opacity="0.6">
        <path d="M860 95 q12 -14 24 0 q12 -14 24 0" />
        <path d="M790 130 q9 -10 18 0 q9 -10 18 0" />
        <path d="M995 165 q9 -10 18 0 q9 -10 18 0" />
      </g>

      {/* ridge 1 (farthest) */}
      <path
        fill="url(#ridge1)"
        d="M0 420 L120 360 L260 410 L380 330 L520 400 L660 350 L820 420 L960 340 L1120 410 L1280 350 L1440 400 L1440 900 L0 900 Z"
      />

      {/* ridge 2 */}
      <path
        fill="url(#ridge2)"
        d="M0 520 L160 430 L300 500 L430 400 L520 460 L600 390 L760 480 L880 410 L1020 490 L1160 420 L1300 480 L1440 440 L1440 900 L0 900 Z"
      />

      {/* ridge 3 - the tall central peak */}
      <path
        fill="url(#ridge3)"
        d="M0 620 L140 560 L260 610 L420 470 L520 560 L640 500 L760 600 L880 530 L1020 610 L1180 540 L1320 600 L1440 560 L1440 900 L0 900 Z"
      />

      {/* ridge 4 (closest, darkest) */}
      <path
        fill="url(#ridge4)"
        d="M0 700 L180 640 L340 690 L480 620 L600 680 L740 610 L900 690 L1040 630 L1200 700 L1320 650 L1440 690 L1440 900 L0 900 Z"
      />

      {/* treeline silhouette at the base */}
      <path
        fill="#27254a"
        d="M0 900 L0 820 L18 800 L30 820 L45 790 L60 820 L80 795 L95 820 L115 785 L135 820 L155 800 L175 820
           L195 790 L215 820 L240 795 L260 820 L285 790 L305 820 L330 795 L350 820
           L375 790 L400 820 L425 795 L450 820 L480 790 L505 820 L530 795 L555 820
           L585 790 L610 820 L635 795 L660 820 L690 790 L715 820 L740 795 L765 820
           L795 790 L820 820 L845 795 L870 820 L900 790 L925 820 L950 795 L975 820
           L1005 790 L1030 820 L1055 795 L1080 820 L1110 790 L1135 820 L1160 795 L1185 820
           L1215 790 L1240 820 L1265 795 L1290 820 L1320 790 L1345 820 L1370 795 L1395 820
           L1420 800 L1440 820 L1440 900 Z"
      />
    </svg>
  );
}
