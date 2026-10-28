package com.smartgn.billing.adapter;

import com.smartgn.billing.application.*;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class FiscalController {
    private final FiscalService fiscal;

    public FiscalController(FiscalService fiscal) {
        this.fiscal = fiscal;
    }

    @GetMapping("/api/v1/billing-documents/{id}/download")
    public ResponseEntity<byte[]> download(
            @RequestAttribute("actor") IdentityPort.Account actor,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "pdf") String format) {
        byte[] data = fiscal.download(actor, id, format);
        String type =
                switch (format) {
                    case "xml" -> "application/xml";
                    case "cdr" -> "application/zip";
                    default -> "application/pdf";
                };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type))
                .header(
                        "Content-Disposition",
                        ContentDisposition.attachment()
                                .filename(id + "." + (format.equals("cdr") ? "zip" : format))
                                .build()
                                .toString())
                .body(data);
    }
}
