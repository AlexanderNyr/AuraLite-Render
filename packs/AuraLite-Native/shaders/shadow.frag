#version 460
// Reference pack: shadow pass fragment stage (present so the program lints
// clean). The pass attaches depth only - depth comes from the fixed-function
// depth attachment, so this stage deliberately writes no color outputs
// (a fragment write to an unattached location 0 is a validation finding).
void main() {}
