"use client";


export function Logo({ compact = false, productName = "BrainServe Connect" }: { compact?: boolean; productName?: string }) {
    return (
        <div className="brand-lockup" aria-label={productName}>
            <div className="brand-mark"><span>B</span></div>
            {!compact && <div><strong>{productName}</strong><small>Workplace Operations</small></div>}
        </div>
    );
}

