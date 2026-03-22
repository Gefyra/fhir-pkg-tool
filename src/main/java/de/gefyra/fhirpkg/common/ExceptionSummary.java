package de.gefyra.fhirpkg.common;

public final class ExceptionSummary {

  private ExceptionSummary() {
  }

  public static String summarizeException(Throwable ex) {
    if (ex == null) {
      return "Unknown error";
    }
    Throwable root = ex;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    String msg = root.getMessage();
    if (msg == null || msg.isBlank()) {
      msg = ex.getMessage();
    }
    if (msg == null || msg.isBlank()) {
      return root.getClass().getSimpleName();
    }
    return msg;
  }
}
