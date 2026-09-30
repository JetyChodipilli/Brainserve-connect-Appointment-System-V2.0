"use client";

import { type Department } from "../../../shared/types/workspace";
import { departmentLogoKey, normalizeDepartmentCode } from "../department-utils";
import Image from "next/image";
import { useState } from "react";

export function DepartmentLogo({ department, size = 30 }: { department: Department; size?: number }) {
    const source = `/department-logos/${departmentLogoKey(department)}.png`;
    const [failedSource, setFailedSource] = useState<string | null>(null);
    const fallbackLabel = (normalizeDepartmentCode(department.code || department.name) || "DEPT")
        .split("_")
        .filter(Boolean)
        .map((part) => part[0])
        .join("")
        .slice(0, 3);

    if (failedSource === source) {
        return <span
            className="department-logo-fallback"
            aria-hidden="true"
            style={{ width: size, height: size, fontSize: Math.max(8, Math.round(size * .28)) }}
        >{fallbackLabel}</span>;
    }

    return <Image
        key={source}
        src={source}
        alt=""
        aria-hidden="true"
        width={size}
        height={size}
        unoptimized
        onError={() => setFailedSource(source)}
        style={{ width: size, height: size, objectFit: "contain" }}
    />;
}

