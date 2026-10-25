/* 2009 OSRS Code Edition.exe: starts the launcher with the bundled Java (jre\bin\javaw.exe), no console window. */
#include <windows.h>
#include <wchar.h>

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR args, int show) {
    wchar_t dir[MAX_PATH], java[MAX_PATH], jar[MAX_PATH], cmd[4 * MAX_PATH];
    GetModuleFileNameW(NULL, dir, MAX_PATH);
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash) *slash = 0;
    swprintf(java, MAX_PATH, L"%ls\\jre\\bin\\javaw.exe", dir);
    swprintf(jar, MAX_PATH, L"%ls\\codeedition\\launcher.jar", dir);
    if (GetFileAttributesW(java) == INVALID_FILE_ATTRIBUTES || GetFileAttributesW(jar) == INVALID_FILE_ATTRIBUTES) {
        MessageBoxW(NULL, L"Some files are missing. Keep this program in the same folder as the jre, game and codeedition folders.",
                    L"2009 OSRS Code Edition", MB_ICONERROR);
        return 1;
    }
    swprintf(cmd, 4 * MAX_PATH, L"\"%ls\" -Xmx256m -Dfile.encoding=UTF-8 -jar \"%ls\"", java, jar);
    STARTUPINFOW si = { sizeof(si) };
    PROCESS_INFORMATION pi;
    if (!CreateProcessW(NULL, cmd, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
        MessageBoxW(NULL, L"Could not start Java from the jre folder.", L"2009 OSRS Code Edition", MB_ICONERROR);
        return 1;
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}
