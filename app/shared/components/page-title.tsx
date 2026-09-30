"use client";


export function PageTitle({ eyebrow, title, detail, action }: { eyebrow: string; title: string; detail: string; action?: React.ReactNode }) {
    return <div className="page-title"><div><span>{eyebrow}</span><h1>{title}</h1><p>{detail}</p></div>{action}</div>;
}

